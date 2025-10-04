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
