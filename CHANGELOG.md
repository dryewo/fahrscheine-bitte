# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).


## [Unreleased]

## [0.4.0] — 2019-01-02
### Added
- Document how to configure caching.
- Document how to configure timeouts for tokeninfo calls.
### Changed
- Handlers created by `make-wrap-oauth2-token-verifier` and `make-oauth2-s1st-security-handler` never throw
  (previously threw if tokeninfo service returned 5xx or on connection errors).
### Removed
- Not using [Hystrix] because it's causing issues with tracing (call is executed on a different thread)
  and because it's not maintained anymore.

## [0.3.0] — 2018-12-18
### Added
- Client middleware support to enable wrapping calls to tokeninfo URL.
### Changed
- Use Clojure 1.10
- Bumped library versions.

## [0.2.2] — 2018-09-03
### Added
- _CHANGELOG.md_ created.
- Automated version bump in _README.md_.
### Changed
- Bumped library versions.

## 0.2.1 — 2018-06-04
Released without _CHANGELOG.md_.


[0.2.2]: https://github.com/dryewo/fahrscheine-bitte/compare/0.2.1...0.2.2
[0.3.0]: https://github.com/dryewo/fahrscheine-bitte/compare/0.2.2...0.3.0

[Hystrix]: https://github.com/Netflix/Hystrix#hystrix-status
[0.4.0]: https://github.com/dryewo/fahrscheine-bitte/compare/0.3.0...0.4.0
[Unreleased]: https://github.com/dryewo/fahrscheine-bitte/compare/0.4.0...HEAD
