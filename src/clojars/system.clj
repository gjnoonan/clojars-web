(ns clojars.system
  (:require
   [clojars.email :refer [simple-mailer]]
   [clojars.notifications :as notifications]
   ;; for defmethods
   [clojars.notifications.deploys]
   [clojars.notifications.mfa]
   [clojars.notifications.token]
   [clojars.oauth.github :as github]
   [clojars.oauth.gitlab :as gitlab]
   [clojars.remote-service :as remote-service]
   [clojars.ring-servlet-patch :as patch]
   [clojars.s3 :as s3]
   [clojars.search :as search]
   [clojars.stats :refer [artifact-stats]]
   [clojars.storage :as storage]
   [clojars.web :as web]
   [com.stuartsierra.component :as component]
   [duct.component.endpoint :refer [endpoint-component]]
   [duct.component.handler :refer [handler-component]]
   [duct.component.hikaricp :refer [hikaricp]]
   [meta-merge.core :refer [meta-merge]]
   [ring.component.jetty :refer [jetty-server]]))

(def base-env
  {:app {:middleware []}
   :http {:configurator patch/use-status-message-header}})

(defrecord StorageComponent [delegate on-disk-repo repo-bucket cdn-token cdn-url]
  storage/Storage
  (-write-artifact [_ path file force-overwrite?]
    (storage/write-artifact delegate path file force-overwrite?))
  (remove-path [_ path]
    (storage/remove-path delegate path))
  (path-exists? [_ path]
    (storage/path-exists? delegate path))
  (path-seq [_ path]
    (storage/path-seq delegate path))
  (artifact-url [_ path]
    (storage/artifact-url delegate path))

  component/Lifecycle
  (start [t]
    (if delegate
      t
      (assoc t
             :delegate (storage/full-storage on-disk-repo repo-bucket
                                             cdn-token cdn-url))))
  (stop [t]
    (assoc t :delegate nil)))

(defn storage-component [on-disk-repo cdn-token cdn-url]
  (map->StorageComponent {:on-disk-repo on-disk-repo
                          :cdn-token cdn-token
                          :cdn-url cdn-url}))

(defn s3-bucket
  [{:keys [access-key-id secret-access-key region] :as cfg} bucket-key]
  (s3/s3-client access-key-id secret-access-key region (get cfg bucket-key)))

(defn base-system
  "Enough of the system for use in scripts."
  [config]
  (-> (component/system-map
       :db            (hikaricp (assoc (:db config) :max-pool-size 20))
       :search        (search/lucene-component)
       :index-factory #(search/disk-index (:index-path config))
       :stats         (artifact-stats)
       :stats-bucket  (s3-bucket (:s3 config) :stats-bucket))
        (component/system-using
         {:search [:index-factory :stats]
          :stats  [:stats-bucket]})))

(defn new-system [config]
  (let [{:as config :keys [github-oauth gitlab-oauth]} (meta-merge base-env config)]
    (-> (merge
         (base-system config)
         (component/system-map
          :app           (handler-component (:app config))
          :clojars-app   (endpoint-component web/clojars-app)
          :github        (github/new-github-service (:client-id github-oauth)
                                                    (:client-secret github-oauth)
                                                    (:callback-uri github-oauth))
          :gitlab        (gitlab/new-gitlab-service (:client-id gitlab-oauth)
                                                    (:client-secret gitlab-oauth)
                                                    (:callback-uri gitlab-oauth))
          :http          (jetty-server (:http config))
          :http-client   (remote-service/new-http-remote-service)
          :mailer        (simple-mailer (:mail config))
          :notifications (notifications/notification-component)
          :repo-bucket   (s3-bucket (:s3 config) :repo-bucket)
          :storage       (storage-component (:repo config) (:cdn-token config) (:cdn-url config))))
        (component/system-using
         {:app           [:clojars-app]
          :clojars-app   [:db :github :gitlab :error-reporter :http-client
                          :mailer :stats :search :storage]
          :http          [:app]
          :notifications [:db :mailer]
          :storage       [:repo-bucket]}))))
