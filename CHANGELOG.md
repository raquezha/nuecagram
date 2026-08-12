# @raquezha/nuecagram

## 0.10.2

### Patch Changes

- e6f3ff6: Require PRs to include a changeset before merge.

## [0.10.0](https://github.com/raquezha/nuecagram/compare/v0.9.5...v0.10.0) (2026-08-12)

### Features

- add MR participant cache storage baseline ([#60](https://github.com/raquezha/nuecagram/issues/60)) ([0388e64](https://github.com/raquezha/nuecagram/commit/0388e649aa4c88bdb87b03b7b84428df477c62d1))
- **ci:** add Renovate workstream config and maintenance policy ([#76](https://github.com/raquezha/nuecagram/issues/76)) ([14962fd](https://github.com/raquezha/nuecagram/commit/14962fdb64527c2814f24fa53bc20811378ea0bc))
- hosted multi-project Nuecagram onboarding ([#50](https://github.com/raquezha/nuecagram/issues/50)) ([d0b9caa](https://github.com/raquezha/nuecagram/commit/d0b9caacca0fb28f1b0151b5702746b03552b592))
- re-enable detekt using isolated CLI execution to bypass K2 conflict ([0e2145e](https://github.com/raquezha/nuecagram/commit/0e2145e53f6cdf1aa3f485d01df8d4c13f35d6b8))

### Bug Fixes

- remove unused imports flagged by detekt ([29848a8](https://github.com/raquezha/nuecagram/commit/29848a8dd856aaaedfcb828dce0ed115778f6be2))
- resolve detekt unused imports and line length issues from cleanup ([fa9f251](https://github.com/raquezha/nuecagram/commit/fa9f2513d78155201159f2f32ea1896fdf5d6ff9))
- resolve detekt unused imports and line length issues from cleanup ([35626f8](https://github.com/raquezha/nuecagram/commit/35626f8048e4476c9eb87d0abae93df76d31030f))
- resolve Ktor deprecations and Jackson annotations for Kotlin 2.4 ([ac2a711](https://github.com/raquezha/nuecagram/commit/ac2a711afb22e99998575ebab98aec3a5d01e165))
- **test:** eliminate async race condition in MergeRequestWebhookTest reviewer caching ([abacc61](https://github.com/raquezha/nuecagram/commit/abacc6153d108acf5823176d87e4a757800bf7ad))

## 0.10.0

### Minor Changes

- 70d0dd5: Introduce changeset pattern and add a semantic version when releasing.
