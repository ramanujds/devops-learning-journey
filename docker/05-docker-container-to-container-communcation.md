## List the containers

```bash
docker ps
```

## Get the ip address of the container

```bash
docker inspect inventory-app 
```

## Communicate with the container

```bash
docker exec -it web curl 172.0.0.3:8080/api/parts
```

## Connect with a container using its name

### Create a network

```bash
docker network create my-app
```

### Connect the containers to the same network

```bash
docker network connect my-app inventory-app
docker network connect my-app web
```

### Curl the inventory service from the web container using its name

```bash
docker exec -it web curl inventory-app:8080/api/parts
``` 


