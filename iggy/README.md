## Iggy local stack

Copy the env file once:

```bash
cp .env.example .env
```

**Start**
```bash
docker compose up -d
```

**Stop**
```bash
docker compose down
```

**Restart a service**
```bash
docker compose restart iggy
```

**Rebuild and restart**
```bash
docker compose up -d --build
```

**Logs**
```bash
docker compose logs -f iggy
```
