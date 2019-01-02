# Fahrscheine, bitte!

[![Build Status](https://travis-ci.org/dryewo/fahrscheine-bitte.svg?branch=master)](https://travis-ci.org/dryewo/fahrscheine-bitte)
[![codecov](https://codecov.io/gh/dryewo/fahrscheine-bitte/branch/master/graph/badge.svg)](https://codecov.io/gh/dryewo/fahrscheine-bitte)
[![Clojars Project](https://img.shields.io/clojars/v/fahrscheine-bitte.svg)](https://clojars.org/fahrscheine-bitte)

A Clojure library for verifying [OAuth2 access tokens].
For using in [Compojure] routes or in [Swagger1st] security handlers.

> "Die Fahrscheine, bitte!" is what *Fahrkartenkontrolleur* says when entering a bus. *Schwarzfahrer* are fearing this.

Access tokens are verified against [Introspection Endpoint]. In the examples in this document the address of
the endpoint is configured through `TOKENINFO_URL` environment variable.

Responses of this endpoint are by default cached for 2 minutes and only for last 100 tokens (configurable).
Creation of the cached token resolver function has to be done by the user using the provided helper function
to enable testability and follow best practices: make state explicit. Please see examples below.

## Usage

```edn
[fahrscheine-bitte "0.4.0"]
```

Examples assume the following:

```clj
(require '[fahrscheine-bitte.core :as oauth2]
         '[mount.core :as m])

(defn log-access-denied-reason [reason]
  (log/info "Access denied: %s" reason))
```

1. Example with [mount] and [Swagger1st]:

```clj
(m/defstate oauth2-s1st-security-handler
  :start (if-let [tokeninfo-url (System/getenv "TOKENINFO_URL")]
           (let [access-token-resolver-fn (oauth2/make-cached-access-token-resolver tokeninfo-url {})]
             (log/info "Checking OAuth2 access tokens against %s." tokeninfo-url)
             (oauth2/make-oauth2-s1st-security-handler access-token-resolver-fn oauth2/check-corresponding-attributes))
           (do
             (log/warn "No TOKENINFO_URL set; NOT ENFORCING SECURITY!")
             (fn [request definition requirements]
               request))))

(m/defstate handler
  :start (-> (s1st/context :yaml-cp "api.yaml")
             (s1st/discoverer)
             (s1st/mapper)
             (s1st/ring oauth2/wrap-reason-logger log-access-denied-reason)
             (s1st/protector {"oauth2" oauth2-s1st-security-handler})
             (s1st/parser)
             (s1st/executor)))
```

In this example we create a security handler that is given to `s1st/protector` to verify tokens on all endpoints that have
`oauth2` security definition in place.
Additionally, we insert a middleware `wrap-log-auth-error` that will log all rejected access attempts.

2. Example with [mount] and [Compojure]:

```clj
(m/defstate wrap-oauth2-token-verifier
  :start (if-let [tokeninfo-url (System/getenv "TOKENINFO_URL")]
           (let [access-token-resolver-fn (oauth2/make-cached-access-token-resolver tokeninfo-url {})]
             (log/info "Checking OAuth2 access tokens against %s." tokeninfo-url)
             (oauth2/make-wrap-oauth2-token-verifier access-token-resolver-fn))
           (do
             (log/warn "No TOKENINFO_URL set; NOT ENFORCING SECURITY!")
             identity)))

(defn make-handler2 []
  (-> (routes
        (GET "/hello" req {:status 200}))
      (wrap-oauth2-token-verifier)
      (oauth2/wrap-log-auth-error log-access-denied-reason)))
```

`(oauth2/make-wrap-oauth2-token-verifier access-token-resolver-fn)` returns a Ring middleware that can be used to
check access tokens against given token introspection endpoint.

### Configuring caching of tokeninfo responses

You can configure caching by passing the following parameters to `make-cached-access-token-resolver`:

* `:ttl-ms` - number of milliseconds to keep cached results. Both positive and negative results are cached.
  However, errored calls to tokeninfo are not cached. Default: 2000.
* `:max-size` - number of results to cache. This has to be limited to avoid running out of memory. Default: 100

Example:

```clj
(let [access-token-resolver-fn (oauth2/make-cached-access-token-resolver tokeninfo-url {:ttl-ms 5000 :max-size 1000})]
  (oauth2/make-wrap-oauth2-token-verifier access-token-resolver-fn))
```

### Wrapping calls to tokeninfo endpoint

For tracing or logging reasons you might want to wrap the actual HTTP GET request to TOKENINFO_URL.

This is possible by giving `:client-middleware` parameter to `oauth2/make-cached-access-token-resolver`:

```clj
;; Define the middleware
(defn wrap-client-tracing [http-get]
  (fn [url params]
    (println "Calling tokeninfo URL:" url)
    (let [res (http-get url params)]
      (println "Tokeninfo result status:" (:status res))
      res)))

;; Use the middleware when creating a token resolver
(let [access-token-resolver-fn (oauth2/make-cached-access-token-resolver tokeninfo-url {:client-middleware wrap-client-tracing})]
  (oauth2/make-wrap-oauth2-token-verifier access-token-resolver-fn))
```

`:client-middleware` is a middleware for a `clj-http.client/get`-like functions:

```clj
(ns clj-http.client)

(defn get [url params]
  ...)
```

Client middleware is a function that accepts a `clj-http.client/get`-like function —
which takes 2 parameters, `url` and `params`, and returns a response map just like `clj-http.client/get` does —
and returns another `clj-http.client/get`-like function.

You can read more in the [clj-http docs](https://github.com/dakrone/clj-http).

By default no client middleware is applied, `clj-http.client/get` is called directly.

#### Customizing HTTP client call

Client middleware can be used to adjust some parameters of `clj-http.client/get` call, for example, to set timeouts:

```clj
(defn wrap-client-timeouts [http-get]
  (fn [url params]
    ;; :conn-timeout and :socket-timeout do not overlap, giving possible duration of up to 2 seconds
    (http-get url (merge params {:conn-timeout 1000 :socket-timeout 1000}))))

;; Middlewares are combined using `comp`, first middleware is entered first
(let [access-token-resolver-fn (oauth2/make-cached-access-token-resolver tokeninfo-url
                                 {:client-middleware (comp wrap-client-tracing wrap-client-timeouts)})]
  (oauth2/make-wrap-oauth2-token-verifier access-token-resolver-fn))

``` 

**WARNING** It's not recommended to wrap the `http-get` call with `try`, as it will disrupt the logic of caching and error reporting.


## License

Copyright © 2019 Dmitrii Balakhonskii

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

[mount]: https://github.com/tolitius/mount
[swagger1st]: https://github.com/zalando-stups/swagger1st
[Compojure]: https://github.com/weavejester/compojure
[Introspection Endpoint]: https://tools.ietf.org/html/rfc7662#section-2
[OAuth2 access tokens]: https://tools.ietf.org/html/rfc6749#section-1.4
