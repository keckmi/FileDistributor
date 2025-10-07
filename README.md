# FileDistributor

**FileDistributor** is a small Java utility that distributes files from a source folder to mapped target folders based on filename keywords defined in a configuration file.

## 🛠 How it works
When you select one or more files, the app checks their names against `config.json`.  
Each key in the config points to a destination folder.  
If a file name contains that key, the file is copied there — preserving metadata.

If no key matches, the app can ask what to do (depending on the `unmatchedPolicy` setting).

## ⚙️ Configuration
Create a `config.json` based on the example:

```bash
cp config.example.json config.json