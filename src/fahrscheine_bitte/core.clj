(ns fahrscheine-bitte.core
  (:require [clj-http.client :as client]
            [clojure.core.memoize :as memo]
            [clojure.core.cache :as cache]
            [com.netflix.hystrix.core :refer [defcommand]]))


(defn extract-access-token
  "Extracts the Bearer token from the Authorization header."
  [request]
  (if-let [authorization (get-in request [:headers "authorization"])]
    (when (.startsWith authorization "Bearer ")
      (.substring authorization (count "Bearer ")))))


(defn check-consented-scopes
  "Checks if every scope is mentioned in the 'scope' attribute of the token info:
   {\"scope\" [\"read\" \"write\"]}"
  [tokeninfo required-scopes]
  (let [consented-scopes (set (get tokeninfo "scope"))]
    (every? #(contains? consented-scopes %) required-scopes)))


(defn check-corresponding-attributes
  "Checks if every scope has a truthy attribute in the token info of the same name:
   {\"read\": true, \"write\": true}"
  [tokeninfo required-scopes]
  (let [scope-as-attribute-true? (fn [scope]
                                   (get tokeninfo scope))]
    (every? scope-as-attribute-true? required-scopes)))


(defcommand fetch-tokeninfo [tokeninfo-url access-token client-middleware]
  (let [response ((client-middleware client/get) tokeninfo-url {:oauth-token      access-token
                                            :as               :json-string-keys
                                            :throw-exceptions false})]
    (if (client/server-error? response)
      (throw (IllegalStateException. (str "tokeninfo endpoint returned status code: " (:status response))))
      response)))


(defn- resolve-access-token
  "Checks with a tokeninfo endpoint for the token's validity and returns the session information if valid.
   Otherwise returns nil."
  [tokeninfo-url access-token client-middleware]
  (let [response (fetch-tokeninfo tokeninfo-url access-token client-middleware)]
    (when (client/success? response)
      (:body response))))


(defn make-cached-access-token-resolver [tokeninfo-url {:keys [ttl-ms
                                                               max-size
                                                               client-middleware]
                                                        :or   {ttl-ms            120000
                                                               max-size          100
                                                               client-middleware identity}}]
  (memo/fifo #(resolve-access-token tokeninfo-url % client-middleware)
             (cache/ttl-cache-factory {} :ttl ttl-ms) :fifo/threshold max-size))


(defn make-oauth2-s1st-security-handler
  "Returns a swagger1st security handler that checks OAuth 2.0 tokens.
   * access-token-resolver-fn takes a token and returns tokeninfo: https://tools.ietf.org/html/rfc7662#section-2.2
   * scope-checker-fn takes tokeninfo and requirements and returns true if scopes in the tokeninfo match the requirements"
  [access-token-resolver-fn scope-checker-fn]
  (fn [request _ requirements]
    (if-let [access-token (extract-access-token request)]
      (if-let [tokeninfo (access-token-resolver-fn access-token)]
        (if (scope-checker-fn tokeninfo requirements)
          (assoc request :tokeninfo tokeninfo)
          {:status 403 ::reason-code :scopes})
        {:status 401 ::reason-code :token-invalid})
      {:status 401 ::reason-code :token-missing})))


(defn make-wrap-oauth2-token-verifier
  "Returns a swagger1st security handler that checks OAuth 2.0 tokens.
   * access-token-resolver-fn takes a token and returns tokeninfo: https://tools.ietf.org/html/rfc7662#section-2.2"
  [access-token-resolver-fn]
  (fn [next-handler]
    (fn [request]
      (if-let [access-token (extract-access-token request)]
        (if-let [tokeninfo (access-token-resolver-fn access-token)]
          (next-handler (assoc request :tokeninfo tokeninfo))
          {:status 401 ::reason-code :token-invalid})
        {:status 401 ::reason-code :token-missing}))))


(def explain-reason-code
  {:scopes        "scopes not granted"
   :token-invalid "invalid access token"
   :token-missing "no access token given"})


(defn wrap-log-auth-error [next-handler logger-fn]
  (fn [request]
    (let [response (next-handler request)]
      (when-let [reason (explain-reason-code (::reason-code response))]
        (logger-fn reason))
      response)))
