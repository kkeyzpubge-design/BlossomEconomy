# BlossomEconomy 1.0.0

Paper 1.21.11 plugin: economy ($), configurable GUI shop, SellGUI, and Buy-Now Auction House.
SQLite database is created automatically at `plugins/BlossomEconomy/economy.db`.

## Build
Requires JDK 21 and Maven 3.9+.
```bash
mvn clean package
```
The shaded plugin jar is `target/BlossomEconomy-1.0.0.jar`.

## Install
1. Stop the server.
2. Copy the jar into `plugins/`.
3. Start the server once; it creates config.yml, messages.yml and economy.db.
4. Edit `plugins/BlossomEconomy/config.yml`, then run `/be reload`.

## Commands
- `/balance` (`/bal`, `/money`)
- `/pay <player> <amount>` (recipient must be online)
- `/baltop`
- `/shop`
- `/sellgui`
- `/ah`
- `/ah sell <price>` (hold the item in your main hand)
- `/be reload` (admin)

## Permissions
`blossomeconomy.balance`, `blossomeconomy.pay`, `blossomeconomy.shop`,
`blossomeconomy.sell`, `blossomeconomy.ah`, `blossomeconomy.admin`.

## Notes
- Shop sell prices and buy prices use the same configured values. Items absent from the shop cannot be sold.
- Auction house is Buy Now only. Listings expire after the configured number of hours.
- This initial release uses an internal economy database. Vault is listed as a soft dependency but Vault economy-provider integration is not included yet; do not remove your existing economy plugin until integration is added and tested.
- Expired listings are returned automatically if the seller is online at expiry. If offline, the current starter version logs the item rather than delivering it; claim/courier support is a planned improvement.
- Test on a backup/test server before production. This is source code; compile it with Maven to produce the .jar.

## Build without local Maven
See [BUILD-ON-GITHUB.md](BUILD-ON-GITHUB.md). The included GitHub Actions workflow builds the JAR in GitHub and uploads it as an artifact.
