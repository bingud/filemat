# Configurable Settings

Settings that can be configured through the admin web UI. These are stored in the database.

Environment variables take precedence over these settings.

## File System

| Setting | Description | Default | Environment Variable Override |
| --- | --- | --- | --- |
| `follow_symbolic_links` | Whether to follow symbolic links when browsing folders | `false` | `FM_FOLLOW_SYMBOLIC_LINKS` |
| `upload_folder_path` | Default folder path for file uploads | `/tmp/filemat` | - |
