## v0.8.0

### ✨ New Features

- **Pipeline Message Consolidation** - Multiple pipeline and job events are now consolidated into a single updating Telegram message per pipeline. No more notification spam!

- **Project Name in Pipeline Messages** - Pipeline notifications now show the project name in the header: `ProjectName • branch • commitSha`

### 🐛 Bug Fixes

- **Failed Job Icon** - Changed failed job icon from ❎ to ❌ for better visibility (#34)

### 📦 Dependencies

| Package | Old | New |
|---------|-----|-----|
| ktor | 2.3.11 | 2.3.13 |
| logback | 1.4.14 | 1.5.18 |
| truth | 1.4.2 | 1.4.4 |
| kotlinter | 4.4.0 | 4.4.1 |
| coroutines | 1.8.0 | 1.8.1 |
| hoplite | 2.8.0.RC3 | 2.8.2 |
| mockk | 1.13.11 | 1.13.12 |
| vendeli | 6.2.0 | 6.6.0 |
| tgbot | 15.0.0 | 15.3.0 |
| gitlab4j-api | 6.0.0-rc.5 | 6.2.0 |

### 📚 Documentation

- Updated README with features table and supported events
- Added comprehensive project structure to AGENTS.md
