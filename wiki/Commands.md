# Commands

Every command lives under `/dispatcher web` and requires **permission level 3** (op). They are
registered on both loaders and on dedicated servers; the `-server` jar has them too.

| Command | What it does |
|---|---|
| `/dispatcher web status` | whether the server is running, where it is bound, how many users are allowlisted, whether Discord login is configured, and any startup warnings |
| `/dispatcher web session <viewer\|planner\|deployer>` | mints a **single-use login link**, valid 5 minutes; click it in chat to copy. The resulting session lasts `Web Session Hours` |
| `/dispatcher web allow <discordId> <none\|viewer\|planner\|deployer>` | grants a tier. `none` is a permanent block (an id with an entry is never auto-enrolled) |
| `/dispatcher web deny <discordId>` | removes the entry entirely — the user can be auto-enrolled again if `Web Default Tier` allows it |
| `/dispatcher web list` | prints the allowlist with tiers and notes |
| `/dispatcher web reload` | re-reads `secrets.json` and `allowlist.json` from disk without a restart |
| `/dispatcher web refresh` | forces the rail-network graphs to be rebuilt on the next poll |

A Discord user id is a long number: enable Developer Mode in Discord and right-click a user to copy
it.

## Typical first-time sequence

```
# turn the web interface on in config/createdispatcher-common.toml, restart, then:
/dispatcher web status
/dispatcher web session deployer          # get in without configuring Discord
/dispatcher web allow 197123456789012345 deployer   # …or allowlist yourself for Discord login
/dispatcher web reload
```

## Getting the item

Not a Dispatcher command, but the usual companion to the above — the Advanced Schedule has no recipe
yet:

```
/give @s createdispatcher:advanced_schedule
```
