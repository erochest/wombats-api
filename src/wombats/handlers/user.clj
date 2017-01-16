(ns wombats.handlers.user
  (:require [io.pedestal.interceptor.helpers :refer [defbefore]]
            [clojure.core.async :refer [chan go >!]]
            [wombats.daos.core :as dao]))

(def ^:private users [])

(defbefore get-users
  "Returns a seq of users"
  [{:keys [response] :as context}]
  (let [ch (chan 1)
        get-users (dao/get-fn :get-users context)]
    (go
      (>! ch (assoc context :response (assoc response
                                             :status 200
                                             :body (get-users)))))
    ch))

(defbefore get-user-by-id
  "Returns a user by searching for its id"
  [{:keys [response request] :as context}]
  (let [ch (chan 1)
        get-user-by-id (dao/get-fn :get-user-by-id context)
        id (get-in request [:path-params :id])]
    (go
      (>! ch (assoc context :response (assoc response
                                             :status 200
                                             :body (get-user-by-id id)))))
    ch))

(defbefore get-user-by-email
  "Returns a user by searching for its email"
  [{:keys [response request] :as context}]
  (let [ch (chan 1)
        get-user-by-email (dao/get-fn :get-user-by-email context)
        email (get-in request [:path-params :email])]
    (go
      (>! ch (assoc context :response (assoc response
                                             :status 200
                                             :body (get-user-by-email email)))))
    ch))

(defbefore get-user-by-access-token
  "Returns a user by searching for its access token"
  [{:keys [response request] :as context}]
  (let [ch (chan 1)
        get-user-by-token (dao/get-fn :get-user-by-access-token context)
        access-token (get-in request [:path-params :access-token])]
    (go
      (>! ch (assoc context :response (assoc response
                                             :status 200
                                             :body (get-user-by-token access-token)))))
    ch))

(defbefore post-user
  "Adds a new user to the db"
  [{:keys [response] :as context}]
  (let [ch (chan 1)
        add-user (dao/get-fn :add-user context)]
    (go
      (>! ch (assoc context :response (assoc response
                                             :status 200
                                             :body @(add-user {:username "emily"})))))
    ch))
