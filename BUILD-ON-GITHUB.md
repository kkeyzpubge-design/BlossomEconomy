# Build the JAR without installing Maven on your PC

This project includes a GitHub Actions workflow that compiles the plugin using Java 21 and Maven in GitHub's build environment.

1. Create a GitHub repository and upload the contents of this ZIP (the files inside the folder, not the ZIP itself).
2. Open the repository's **Actions** tab.
3. Select **Build BlossomEconomy** and press **Run workflow** (or wait for the automatic build after uploading files).
4. When the run finishes successfully, open that run and download the **BlossomEconomy-JAR** artifact.
5. Extract the artifact ZIP. It contains `BlossomEconomy-1.0.0.jar`.
6. Stop your test server, put the JAR in `plugins/`, and start the server again.

Use a backup/test server first. This is an experimental first version, not a production-tested release. The current version's auction expiry handling and Vault integration have limitations described in README.md.
