# Configurable Settings

Settings that can be configured through the admin web UI. These are stored in the database.

Environment variables take precedence over these settings.

## File System

| Setting | Description | Default | Environment Variable Override |
| --- | --- | --- | --- |
| `follow_symbolic_links` | Whether to follow symbolic links when browsing folders | `false` | `FM_FOLLOW_SYMBOLIC_LINKS` |
| `upload_folder_path` | Default folder path for file uploads | `/tmp/filemat` | - |
| `is_thumbnail_cache_enabled` | Whether generated thumbnails are stored on disk | `false` | - |
| `thumbnail_cache_folder_path` | Folder for cached thumbnail files | (empty) | - |
| `thumbnail_cache_max_size_mb` | Max thumbnail cache size in megabytes | (empty) | - |
| `thumbnail_cache_max_age` | Max thumbnail cache entry age in seconds | (empty) | - |
| `content_base_url` | Absolute URL prefix for downloads, uploads, and thumbnails | (empty) | `FM_CONTENT_BASE_URL` |
| `content_base_url_for_unauthenticated` | Give unauthenticated users the content base URL | `false` | `FM_CONTENT_BASE_URL_FOR_UNAUTHENTICATED` |
