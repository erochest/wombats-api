(ns wombats.game.decisions.shoot
  (:require [wombats.game.decisions.helpers :as dh]
            [wombats.arena.utils :as au]
            [wombats.game.utils :as gu]))

(defn- shot-should-progress?
  "Returns a boolean indicating if a shot should continue down it's path"
  [should-progress? cell-at-point remaining-damage]
  (boolean (and should-progress?
                (> remaining-damage 0))))

(defn- damage-cell
  [cell damage]
  (let [updated-hp (- (:hp cell) damage)
        destroyed? (< updated-hp 1)]
    (if destroyed?
      (au/create-new-contents :open)
      (assoc cell :hp updated-hp))))

(defn- get-shot-metadata
  [shooter]
  {:type :shot
   :owner-id (:uuid shooter)
   :color (get shooter :color "black")
   :orientation (:orientation shooter)
   :decay 1})

(defn- get-explosion-metadata
  []
  {:type :explosion
   :decay 1})

(defn- update-game-state
  [game-state
   {cell-contents :contents
    cell-metadata :meta}
   cell-coords
   damage
   shooter]

  (let [wombat-victim? (= (:type cell-contents) :wombat)
        contains-hp? (boolean (:hp cell-contents))
        cell-update (when contains-hp?
                      (damage-cell cell-contents damage))
        destroyed? (and contains-hp?
                        (= (:type cell-update) :open))
        wombat-shooter? (= (:type shooter) :wombat)]

    ;; Always update the cell metadata to attach the shot
    (cond-> (update-in game-state [:game/frame :frame/arena]
                       #(au/update-cell-metadata %
                                                 cell-coords
                                                 (conj cell-metadata
                                                       (get-shot-metadata shooter))))

      ;; If the cell is able to be damaged, apply damage and update the arena
      contains-hp?
      (update-in [:game/frame :frame/arena]
                 #(au/update-cell-contents %
                                           cell-coords
                                           cell-update))

      ;; If the cell was destroyed, attach explosion metadata
      destroyed?
      (update-in [:game/frame :frame/arena]
                 #(au/update-cell-metadata %
                                           cell-coords
                                           (conj cell-metadata
                                                 (get-explosion-metadata))))

      ;; If the affected cell was another player, updated messages
      wombat-victim?
      (update-in [:game/players (:uuid cell-contents)]
                 (fn [player]
                   ;; TODO #76 Update how many times i've been hit / killed
                   player))

      ;; If the shooter hit something, update their stats
      (and contains-hp? wombat-shooter?)
      (update-in [:game/players (:uuid shooter) :player/stats]
                 (fn [stats]
                   (let [item-hit (:type cell-contents)]
                     (cond-> (update stats :stats/shots-hit inc)
                       ;; Update wombat hit stats
                       (= item-hit :wombat)
                       (-> (update :stats/wombats-hit inc)
                           ;; TODO #333 Pull from config
                           (update :stats/score + 3))

                       ;; Update zakano hit stats
                       (= item-hit :zakano)
                       (-> (update :stats/zakano-hit inc)
                           ;; TODO #333 Pull from config
                           (update :stats/score + 2))

                       ;; Update wood-barrier hit stats
                       (= item-hit :wood-barrier)
                       (-> (update :stats/wood-barriers-hit inc)
                           ;; TODO #333 Pull from config
                           (update :stats/score inc))))))

      ;; If the shooter was a player and they destroyed something, update their stats
      (and destroyed? wombat-shooter?)
      (update-in [:game/players (:uuid shooter) :player/stats]
                 (fn [stats]
                   (case (:type cell-contents)
                     :wombat (-> stats
                                 (update :stats/wombats-destroyed inc)
                                 ;; TODO #333 Pull from config
                                 (update :stats/score + 15))
                     :zakano (-> stats
                                 (update :stats/zakano-destroyed inc)
                                 ;; TODO #333 Pull from config
                                 (update :stats/score + 10))
                     :wood-barrier (-> stats
                                       (update :stats/wood-barriers-destroyed inc)
                                       ;; TODO #333 Pull from config
                                       (update :stats/score + 2))
                     stats))))))

(defn- process-shot
  "Process a cell that a shot passes through"
  [{:keys [game-state
           shot-damage
           should-progress?
           shooter] :as shot-state}
   shot-cell-coords]
  (let [arena (get-in game-state [:game/frame :frame/arena])
        cell-at-point (gu/get-item-at-coords shot-cell-coords arena)]
    (if (shot-should-progress? should-progress? cell-at-point shot-damage)
      (let [cell-hp (get-in cell-at-point [:contents :hp] 0)
            remaining-shot-damage (Math/max 0 (- shot-damage cell-hp))
            damage (- shot-damage remaining-shot-damage)]

        {:game-state (update-game-state game-state
                                        cell-at-point
                                        shot-cell-coords
                                        damage
                                        shooter)
         :shot-damage remaining-shot-damage
         :should-progress? true
         :shooter shooter})
      (assoc shot-state :should-progress? false))))

(defn shoot
  [game-state metadata decision-maker-state]

  (let [decision-maker-coords (dh/get-decision-maker-coords decision-maker-state)
        decision-maker-contents (dh/get-decision-maker-contents decision-maker-state)
        direction (gu/orientation-to-direction (:orientation decision-maker-contents))
        {shot-distance :arena/shot-distance
         shot-damage :arena/shot-damage} (:game/arena game-state)
        shoot-coords (gu/draw-line-from-point (get-in game-state [:game/frame :frame/arena])
                                              decision-maker-coords
                                              direction
                                              ;; TODO #333 Add to config
                                              (or shot-distance 5))]
    (cond-> (:game-state
             (reduce process-shot
                     {:game-state game-state
                      :shot-damage shot-damage
                      :should-progress? true
                      :shooter decision-maker-contents}
                     shoot-coords))

      ;; Update shooter stats if the shooter is a player
      (= (:type decision-maker-contents) :wombat)
      (update-in [:game/players (:uuid decision-maker-contents) :player/stats]
                 (fn [stats]
                   (-> stats
                       (update :stats/shots-fired inc)))))))
