# Docker deployment

### Compose (recommended)

Create a file called `docker-compose.yml`, put in the compose configuration, then run `docker compose up -d`.<br>
Running `compose up` again will download the newest Filemat version.

```yaml
name: filemat

services:
  filemat:
    image: bingud/filemat:latest
    container_name: filemat
    user: 1111:1111                             # Example user and group
    ports:
      - "12345:8080"                            # Exposes Filemat on port 12345 on the host machine
    volumes:
      - /srv/filemat-data:/var/lib/filemat      # Mount for Program files / database
      - /host-folder:/container-folder          # Example mount
    restart: unless-stopped
    pull_policy: always                         # Always downloads the latest Filemat container
    environment:
        FM_PRINT_LOGS: "true"
```

You should customize these properties:
- User
- Port
- Volumes (host files that will be available to Filemat) <br>(Mount the container folder `/var/lib/filemat` to persist Filemat application data)
- Environment variables

Make sure to set correct restrictive permissions for the Filemat data folder (mounted on the host machine). 

---

### Run command

Customize the properties listed above.

```bash
sudo docker run -d \
  --name filemat \
  -p 12345:8080 \
  --user 1111:1111 \
  -v /srv/filemat-data:/var/lib/filemat \
  -v /host-folder:/container-folder \
  --restart unless-stopped \
  -e FM_PRINT_LOGS=true \
  bingud/filemat:latest
```

---

### Docker Desktop on Windows

The image is a Linux image, and Docker Desktop runs it in WSL 2. For Filemat as a Windows program, see the [Windows guide](</docs/windows.md>).

- Mount Windows folders with their Windows path: `-v C:\Data:/data`, or in compose `- C:\Data:/data`.
- `user: 1111:1111` doesn't limit access to Windows folders. Docker Desktop gives the container access to every mounted Windows file.
- **Permissions can get lost.** Filemat recognizes a file by its inode number, and Docker Desktop may give files in a mounted Windows folder new inode numbers after a restart. Permissions and shares on those files then stop working. Filemat logs a warning at startup when an exposed folder is on such a mount.
- For best results, keep the files in a Docker volume or in the WSL file system (`\\wsl$\<distro>\home\...`, mounted as `/home/...` from inside WSL), not in a Windows folder.
- The same applies to `/mnt/c` and other Windows drives seen from inside WSL.

---

### Reverse proxy

When Filemat runs behind a reverse proxy, these headers must be forwarded:

- `X-Forwarded-For`: client IP, for rate limiting and audit logs. If missing, the connection IP is used.

If you also configure a different content server URL, Filemat requires these headers:

- `X-Forwarded-Proto`: the browser scheme (`https` or `http`).
- `Host`
