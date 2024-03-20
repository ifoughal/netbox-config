
# Netbox Config

This repository contains the necessary configuration files to run Netbox.

This is then merged with the public repository to override the env, volumes and override compose parameters

it also contains backup, deploy and provision host jenkins pipelines for netbox deployment
## How to Run

### Option 1: Through CLI:
1. Set the environment variable `VERSION` to the desired version of Netbox.
```bash
export VERSION=v1.2.3
```

2. Pull the Netbox Docker image:
a. with public regristry:
```bash
docker-compose pull
```

b. with local regristry:
```bash
REGISTRY=my-registry.com docker-compose pull
```

3. Start the Netbox service:
```bash
docker-compose up -d
```

4. Set up a superuser for Netbox:
```bash
docker-compose exec netbox /opt/netbox/netbox/manage.py createsuperuser
```

### Option 2: Through jenkins:
1. fill the inputs through the pipeline parameters
2. buld

That's it! You should now have Netbox up and running with a superuser account set up.


## Contributing

Contributions are welcome! If you would like to contribute to this project, please follow these steps:

1. Fork the repository.
2. Create a new branch for your feature or bug fix.
3. Make your changes and commit them to your branch.
4. Push your branch to your forked repository.
5. Open a pull request to the original repository.

Please ensure that your code follows the project's coding conventions and includes appropriate tests. Thank you for your contribution!
