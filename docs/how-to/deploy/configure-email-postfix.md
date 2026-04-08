# Configure Postfix for Docker

If `server-streampack` runs in Docker and Postfix runs on the host, the container cannot reach a Postfix listener bound only to `127.0.0.1:25`.

Check the listener:

```bash
ss -ltnp | grep ':25'
```

If it shows only `127.0.0.1:25`, Postfix is host-loopback only.

## Listen on a Docker-Reachable Interface

Set Postfix to listen beyond loopback:

```bash
sudo postconf -e 'inet_interfaces = all'
sudo systemctl restart postfix
```

Then restrict relay access. Do not create an open relay.

Find the active Compose subnet:

```bash
docker network inspect deploy_default --format '{{json .IPAM.Config}}'
```

If the subnet is `172.21.0.0/16`, configure:

```bash
sudo postconf -e 'mynetworks = 127.0.0.0/8 [::1]/128 172.21.0.0/16'
sudo systemctl reload postfix
```

Use the actual subnet from `docker network inspect`; do not assume `172.17.0.0/16`.

## Configure the App

Use:

```dotenv
MAIL_ENABLED=true
MAIL_HOST=host.docker.internal
MAIL_PORT=25
MAIL_FROM=noreply@example.com
```

On Linux Docker Engine, the Compose service should include:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

Test from the container:

```bash
docker compose exec server-streampack sh -lc 'curl -v telnet://host.docker.internal:25'
```
