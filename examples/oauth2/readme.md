A simple openid connect server.

This is a full-fledged example showing off keg's capabilities in a real-world
example.

## Configuration

Config is done via environment variables.

| Env var | Description               | Default |
|---------|---------------------------|---------|
| HOST    | IP address to bind to     | ::      |
| PORT    | port to bind to           | 8080    |
| KEY     | enable HTTPS and use this key file | unset      |
| CERT    | use this certificate      | unset   |
| SECRET  | secret used to sign cookies  | unset (uses temp secret for each run)  |
| DB      | sqlite database           | sso.db  |
| SESSION_TTL | duration of browser sessions | 7d |

## Running

- dev mode: `mill examples.oauth2 serve`
- build image: `mill examples.oath2.image`
- run image: `mill -p 8080:8080 examples.oath2.image`

podman run -p 8080:8080 --volume sso:/data -e DB=/data/sso.db -e SECRET=pX65+r3GEING5J6sz8zJ4tvK+6p34IxpoNpFNMfThVA=  localhost/sso:latest serve

podman run -it -p 8080:8080 --volume sso:/data -e DB=/data/sso.db -e SECRET=pX65+r3GEING5J6sz8zJ4tvK+6p34IxpoNpFNMfThVA=  localhost/sso:latest add-admin
