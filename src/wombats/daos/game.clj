(ns wombats.daos.game
  (:require [datomic.api :as d]
            [taoensso.timbre :as log]
            [taoensso.nippy :as nippy]
            [wombats.constants :refer [initial-stats]]
            [wombats.game.core :as game]
            [wombats.handlers.helpers :refer [wombat-error]]
            [wombats.game.utils :refer [decision-maker-state]]
            [wombats.sockets.game :as game-sockets]
            [wombats.daos.helpers :refer [get-entity-by-prop
                                          get-entity-id
                                          gen-id
                                          get-entities-by-prop
                                          retract-entity-by-prop]]
            [wombats.handlers.helpers :refer [wombat-error]]
            [wombats.daos.user :refer [get-user-entity-id
                                       public-user-fields]]))

;; Note about game states:
;;
;; There are four different states that a game can be in.
;; 1. :pending-open   (the game is awaiting players to join and the game has not yet begun)
;; 2. :pending-closed (the game is now longer accepting new player enrollment and the game has not yet begun)
;; 3. :active         (the game is underway)
;; 4. :closed         (the game is finished and archived)
;;
;; Right now these states are hardcoded in their respective DAOs, but this logic should be captured in a FSM
;; like https://github.com/ztellman/automat or even a simple hand rolled one.
;;
;; Looks something like this;
;;
;; 1 → 3 → 4
;; ↓   ↑
;; 2 → ↑

(def game-projection
  ;; This is what is exposed to the client
  '[*
    {:game/arena [:db/id *]}
    {:game/players [*
                    {:player/user [:user/github-username]}
                    {:player/wombat [*]}]}])

(def game-state-projection
  ;; This is what our API uses internally
  '[*
    {:game/arena [:db/id *]}
    {:game/players [*
                    {:player/user [:db/id
                                   :user/github-username
                                   :user/github-access-token]}
                    {:player/wombat [*]}
                    {:player/stats [*]}]}
    {:game/frame [*]}])

(defn- merge-player
  [player-map player additional-state]
  (assoc player-map
         (:player/id player)
         (if additional-state
           (merge player additional-state)
           player)))

(defn- format-players
  ([game]
   (format-players game nil))
  ([game additional-player-state]
   (update game :game/players
           (fn [players]
             (if players
               (reduce
                #(merge-player %1 %2 additional-player-state)
                {} players)
               {})))))

(defn filter-game-password
  [conn]
  (d/filter (d/db conn)
            (fn [db datom]
              (not= (d/entid db :game/password)
                    (.a datom)))))

(defn get-games-by-eids
  [conn]
  (fn [game-eids]
    (map
     format-players
     (-> (d/pull-many
          (filter-game-password conn)
          game-projection
          game-eids)))))

(defn get-game-eids-by-status
  [conn]
  (fn [status]
    (let [formatted-status (if (vector? status)
                             (map keyword status)
                             [(keyword status)])]
      (apply concat
             (d/q '[:find ?games
                    :in $ [?status ...]
                    :where [?games :game/status ?status]]
                  (d/db conn)
                  formatted-status)))))

(defn get-game-eids-by-player
  [conn]
  (fn [user-id]
    (let [user-ids (if (vector? user-id)
                     user-id
                     [user-id])]
      (apply concat
             (d/q '[:find ?games
                    :in $ [?user-ids ...]
                    :where [?users :user/id ?user-ids]
                    [?players :player/user ?users]
                    [?games :game/players ?players]]
                  (d/db conn)
                  user-ids)))))

(defn get-all-game-eids
  [conn]
  (fn []
    (apply concat
           (d/q '[:find ?games
                  :in $
                  :where [?games :game/id]]
                (d/db conn)))))

(defn get-all-games
  [conn]
  (fn []
    ((get-games-by-eids conn)
     ((get-all-game-eids conn)))))

(defn get-all-pending-games
  [conn]
  (fn []
    ((get-games-by-eids conn)
     ((get-game-eids-by-status conn) [:pending-open :pending-closed]))))

(defn get-game-by-id
  [conn]
  (fn [game-id]
    (-> (get-entity-by-prop conn :game/id game-id game-projection)
        format-players)))

(defn get-game-state-by-id
  [conn]
  (fn [game-id]
    (let [game-state (get-entity-by-prop conn
                                         :game/id
                                         game-id
                                         game-state-projection)]
      (-> game-state
          (update-in [:game/frame :frame/arena] nippy/thaw)
          (format-players {:state decision-maker-state})))))

(defn add-game
  "Adds a new game entity to Datomic"
  [conn]
  (fn [game arena-eid game-arena]

    (let [frame-tmp-id (d/tempid :db.part/user)
          frame-trx {:db/id frame-tmp-id
                     :frame/frame-number 0
                     :frame/round-number 1
                     :frame/id (gen-id)
                     :frame/arena (nippy/freeze game-arena)}
          game-trx (merge game
                          {:db/id (d/tempid :db.part/user)
                           :game/frame frame-tmp-id
                           :game/arena arena-eid})]
      (d/transact-async conn [frame-trx
                              game-trx]))))

(defn retract-game
  [conn]
  (fn [game-id]
    (retract-entity-by-prop conn :game/id game-id)))

(defn add-player-to-game
  [conn]
  (fn [{game-eid :db/id
       game-id :game/id}
      user-eid
      wombat-eid
      color]

    @(d/transact conn [[:player-join
                        game-eid
                        user-eid
                        wombat-eid
                        color
                        initial-stats]])

    (game-sockets/broadcast-game-info ((get-game-state-by-id conn) game-id))))

(defn- update-frame
  [conn]
  (fn [frame players]
    (let [frame-trx (-> frame (update :frame/arena nippy/freeze))
          stats-trxs (vec (map (fn [[_ {stats :player/stats}]]
                                 (assoc stats
                                        :stats/frame-number
                                        (:frame/frame-number frame)))
                               players))
          final-trx (conj stats-trxs frame-trx)]
      ;; NOTE: This should probably stay transact (not transact-async)
      ;; until we can guarantee frames to be saved in sequential order
      ;; at a DB level.
      (d/transact conn final-trx))))

(defn- close-round
  [conn]
  (fn [{:keys [:game/id :game/frame :game/status]}]

    (let [frame-trx (-> frame (update :frame/arena nippy/freeze))
          game-trx {:game/id id
                    :game/status status}]

      (d/transact-async conn [frame-trx game-trx]))))

(defn- close-game
  [conn]
  (fn [{:keys [:game/id :game/end-time]}]

    (d/transact-async conn [{:game/id id
                             :game/status :closed
                             :game/end-time end-time}])))

(defn start-game
  "Transitions the game status to active"
  [conn aws-credentials lambda-settings]
  (fn [game-id]
    (let [game-state ((get-game-state-by-id conn) game-id)
          {game-eid :db/id} game-state]

      (when (= 0 (count (:game/players game-state)))
        (wombat-error {:code 101006
                       :details {:game-id game-id}}))

      ;; We put this in a future so that it gets run on a separate thread
      (future
        (try
          (game/start-round game-state
                            {:update-frame (update-frame conn)
                             :close-round (close-round conn)
                             :close-game (close-game conn)
                             :round-start-fn (start-game conn aws-credentials lambda-settings)}
                            aws-credentials
                            lambda-settings)
          (catch Exception e
            (log/error e))))

      (d/transact-async conn [{:game/id game-id
                               :game/status :active}]))))

(defn get-player-from-game
  "Returns the player entity from a specified game"
  [conn]
  (fn [game-id user-id]
    (let [player (ffirst
                  (d/q '[:find (pull ?player [*])
                         :in $ ?game-id ?user-id
                         :where [?user :user/id ?user-id]
                                [?player :player/user ?user]
                                [?game :game/players ?player]]
                       (d/db conn)
                       game-id
                       user-id))]
      player)))
