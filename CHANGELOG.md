# Changelog

All notable changes to Nuecagram will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Standardized open-source PR title guidelines and GitHub PR template (`.github/PULL_REQUEST_TEMPLATE.md`).
- Automated main-merge deployment with Docker image digest persistence in `/opt/nuecagram/.env`.

### Changed
- Simplified server deployment to use a single `compose.yaml` and single `/opt/nuecagram/.env` file.
- Replaced bcrypt password hashing with `PLATFORM_ADMIN_PASSWORD` plain text environment configuration.
- Removed legacy package-oriented Changesets release machinery.

## [0.11.0] - 2026-08-12

### Added
- Forum topic thread preservation for Telegram commands.
- Interactive `/help` text and private chat installation guards.

### Changed
- Standardized GitHub Actions runtime on Node 24.
- Automated deployment script for production server.

## [0.10.0] - 2026-08-12

### Added
- Hosted multi-project Nuecagram onboarding and installation repository.
- Renovate dependency maintenance policy and configuration.
