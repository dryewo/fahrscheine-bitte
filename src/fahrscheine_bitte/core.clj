(ns fahrscheine-bitte.core
  (:require [clj-http.client :as http]
            [clojure.core.memoize :as memo]
            [clojure.core.cache :as cache]))


(defn extract-access-token
  "Extracts the Bearer token from the Authorization header.
   Returns either nil or the token string."
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


(defn fetch-tokeninfo
  "Returns a response with status 2xx or 4xx,
   throws an exception on 5xx or when http/get throws.
   Returned results are cacheable, thrown ones are not."
  [tokeninfo-url access-token client-middleware]
  ;; http/get with {:throw-exceptions false} throws only on connection errors or timeouts
  (let [response ((client-middleware http/get) tokeninfo-url {:oauth-token      access-token
                                                              :as               :json-string-keys
                                                              :throw-exceptions false})]
    ;; If returned 5xx, throw an exception, so that the response is not cached
    ;; Caching 4xx is ok
    (if (http/server-error? response)
      (throw (IllegalStateException. (str "tokeninfo endpoint returned status code: " (:status response))))
      response)))


(defn resolve-access-token
  "Checks with a tokeninfo endpoint for the token's validity.
   Returns the session information if valid (2xx), otherwise (4xx) returns nil.
   Throws if fetch-tokeninfo throws (which it does on 5xx or on connection errors/timeouts)."
  [tokeninfo-url access-token client-middleware]
  (let [response (fetch-tokeninfo tokeninfo-url access-token client-middleware)]
    (when (http/success? response)
      (:body response))))


(defn make-cached-access-token-resolver
  "Returns a function that throws or returns whatever resolve-access-token throws or returns.
   Only returned results are cached."
  [tokeninfo-url {:keys [ttl-ms
                         max-size
                         client-middleware]
                  :or   {ttl-ms            120000
                         max-size          100
                         client-middleware identity}}]
  ;; Caches only the results returned normally:
  ;; 2xx:                                 response body is cached
  ;; 4xx:                                 nil is cached
  ;; exception from resolve-access-token: nothing is cached
  (memo/fifo #(resolve-access-token tokeninfo-url % client-middleware)
             (cache/ttl-cache-factory {} :ttl ttl-ms) :fifo/threshold max-size))


(defn make-wrap-oauth2-token-verifier
  "Returns a swagger1st security handler that checks OAuth 2.0 tokens.
   * access-token-resolver-fn takes a token and returns tokeninfo: https://tools.ietf.org/html/rfc7662#section-2.2
   access-token-resolver-fn is presumably created by make-cached-access-token-resolver."
  [access-token-resolver-fn]
  (fn [next-handler]
    ;; Never throws, sometimes calls next-handler, sometimes returns response with 401 or 504
    ;; token missing: returns 401
    ;; then, depending on tokeninfo call result:
    ;;   2xx (can come from cache)                           - injects returned tokeninfo into the request,
    ;;                                                         calls next handler
    ;;   4xx (can come from cache)                           - returns a 401 response
    ;;   thrown (because of 5xx or connection error/timeout) - returns a 504 response
    (fn [request]
      (let [access-token (extract-access-token request)]
        (if-not access-token
          {:status 401 ::reason-code :token-missing}
          (let [tokeninfo (try
                            (access-token-resolver-fn access-token)
                            (catch Exception e
                              e))]
            (cond
              (nil? tokeninfo)
              {:status 401 ::reason-code :token-invalid}

              (instance? Exception tokeninfo)
              (let [message (str "access-token-resolver-fn call threw an exception: " (str tokeninfo))]
                {:status 504 :body message ::reason-code message})

              :else
              (next-handler (assoc request :tokeninfo tokeninfo)))))))))


(defn make-oauth2-s1st-security-handler
  "Returns a swagger1st security handler that checks OAuth 2.0 tokens.
   * access-token-resolver-fn takes a token and returns tokeninfo: https://tools.ietf.org/html/rfc7662#section-2.2
   * scope-checker-fn takes tokeninfo and requirements and returns true if scopes in the tokeninfo match the requirements
   access-token-resolver-fn is presumably created by make-cached-access-token-resolver."
  [access-token-resolver-fn scope-checker-fn]
  ;; Never throws.
  ;; Returns the same request with :tokeninfo injected if everything is ok
  ;; or a response map with :status key if something went wrong
  (fn [request _ requirements]
    ;; Very sophisticated way to reuse logic from make-wrap-oauth2-token-verifier
    ;; Avoiding code duplication at all costs
    (let [middleware-that-calls-tokeninfo                  (make-wrap-oauth2-token-verifier access-token-resolver-fn)
          handler-that-checks-scopes-in-injected-tokeninfo (middleware-that-calls-tokeninfo
                                                             ;; This handler expects :tokeninfo key present in request
                                                             ;; Just checks if scopes are satisfied
                                                             ;; Returns original request if everything is ok,
                                                             ;;  response with 403 otherwise
                                                             (fn [req]
                                                               (if-not (scope-checker-fn (:tokeninfo req) requirements)
                                                                 {:status 403 ::reason-code :scopes}
                                                                 req)))]
      (handler-that-checks-scopes-in-injected-tokeninfo request))))


(defn explain-reason-code
  "If code is one of the supported codes, returns the explanation.
   If code is a string, returns it as is."
  [code]
  (if (keyword? code)
    (get {:scopes        "scopes not granted"
          :token-invalid "invalid access token"
          :token-missing "no access token given"}
         code)
    code))


(defn wrap-log-auth-error [next-handler logger-fn]
  (fn [request]
    (let [response (next-handler request)]
      (when-let [reason (explain-reason-code (::reason-code response))]
        (logger-fn reason))
      response)))
