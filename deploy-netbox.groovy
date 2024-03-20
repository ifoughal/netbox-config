def COMPOSER = "docker compose"
def node_name = 'node-1'

def error_message = ''

def docker_compose_version = "2.21.0"

def TARGET_REPOS_DIR = "/home/netbox/containers"

def remote_netbox_docker_repo = "ssh://git@local-git/netbox-docker.git"
def remote_netbox_config_repo = "ssh://git@local-git/netbox-config.git"

def certificate_location = "/home/.ssh/gitlab"

def LATEST_BACKUP = []
def ALL_BACKUPS = []

def POST_DEPLOY_SLEEP_TIMER = 300

def NETBOX_SUBNET = "192.168.254.0/24"


// download the packages for docker:
//       https://download.docker.com/linux/ubuntu/dists/focal/pool/stable/amd64/
//                   ./containerd.io_<version>_<arch>.deb \
//                   ./docker-ce_<version>_<arch>.deb \
//                   ./docker-ce-cli_<version>_<arch>.deb \
//                   ./docker-buildx-plugin_<version>_<arch>.deb \
//                   ./docker-compose-plugin_<version>_<arch>.deb
// install the packages:
//       sudo dpkg -i *.deb

// parse through backups on the local report on the node, the backups must be committed in this case.
// Alternatively a path for the backup can be provided, but the logic must be changed so that we ssh to target backup and use the same logic instead of doing it locally
node(node_name) {

    println("execution node is: $node_name")
    wrap([$class: 'BuildUser']) {
        current_branch = scm.branches[0].name
        git_url = scm.getUserRemoteConfigs()[0].getUrl()
        git_credentials = scm.getUserRemoteConfigs()[0].getCredentialsId()
        USER_ID = env.BUILD_USER_ID
        WORKSPACE = env.WORKSPACE
    }

    // checkout current build with entered branch parameters:
    checkout(
        [
            $class: 'GitSCM',
            branches: [[name: current_branch]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[
                $class: 'SubmoduleOption',
                disableSubmodules: false,
                parentCredentials: true,
                recursiveSubmodules: true,
                reference: '',
                trackingSubmodules: false
            ]],
            submoduleCfg: [],
            userRemoteConfigs: [[
                credentialsId: git_credentials,
                url: git_url
            ]]
        ]
    )

    LATEST_BACKUP = sh(
        script: "ls -t ./backups",
        returnStdout: true
    ).trim()

    println("most recent backup is: '${LATEST_BACKUP}'")

    ALL_BACKUPS = sh(
        script: "ls -r ./backups",
        returnStdout: true
    ).trim()

    ALL_BACKUPS = ALL_BACKUPS.split("\n")
}



String array_to_string(array){
    def new_array = []
    for (int i = 0; i < array.size(); i++) {
        new_array[i] = "\"${array[i]}\"".toString()
    }

    return """
        return ${new_array}
    """
}

properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            name: 'DB_TO_RESTORE',
            description: 'select which database you wish to deploy:',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        return ["failed to parse backups dir"]
                    '''
                ],
                script: [
                    classpath: [],
                    sandbox: true,
                    script: array_to_string(ALL_BACKUPS)

                ]
            ]
        ],
    ])
])





pipeline {
    agent {
        label {
            label node_name
            // customWorkspace workspace_path
        }
    }

    options {
        quietPeriod(0)
    }

    parameters {
        booleanParam(
            name: 'PROVISION_HOST',
            description: 'Provision target netbox-docker host with the pre-requisites.',
            defaultValue: false
        )

        booleanParam(
            name: 'PULL_LATEST_BACKUPS_ONLY',
            description: 'If selected, will update the GUI with the latest backups without deploying.',
            defaultValue: false
        )

        choice(
            name: 'netbox_version',
            choices: [ 'v3.6.3', 'v3.5.6' ],
            description: 'version of netbox-docker to be used for the deployment'
        )

        choice(
            name: 'NETBOX_DOCKER_VERSION',
            choices: [ '2.7.0', '2.6.1' ],
            description: 'version of netbox to be deployed.'
        )

        string(
            name: 'destination_ip_address',
            description: 'IP address of the netbox host',
            defaultValue: "10.10.10.1"
        )
        ////////////////////////////////////////////////////////////
        string(
            name: 'REGISTRY_URL',
            description: 'docker registry URL',
            defaultValue: "local-registry.local"
        )
        ////////////////////////////////////////////////////////////
        // string(
        //     name: 'REGISTRY_USERNAME',
        //     description: 'Username to login to docker registry',
        //     defaultValue: "ifoughal"
        // )
        // password(
        //     name: 'REGISTRY_PASSWORD',
        //     description: 'Password to login to docker registry',
        //     defaultValue: ""
        // )
        choice(
            name: 'REGISTRY_CREDENTIALS',
            choices: ['local_registry'],
            description: 'crendentatials ID of the username/password to connect to the docker registry'
        )

        choice(
            name: 'SELECTED_SSH_CREDENTIALS',
            choices: ['priviledged_user', 'netbox-user'],
            description: 'crendentatials ID of the username/private-key to be used to ssh to target.'
        )
        ////////////////////////////////////////////////////////////
    }

    environment {
        SSH_CREDENTIALS = credentials("${SELECTED_SSH_CREDENTIALS}")
        REGISTRY_CREDENTIALS = credentials("${REGISTRY_CREDENTIALS}")
        GIT_CREDENTIALS = credentials("gitlab-key")
        ECO_CREDENTIALS = credentials("eco_token")

    }


    stages {
        stage ("Initialising pipeline") {
            steps {
                script{
                    if (PULL_LATEST_BACKUPS_ONLY == "true") {
                        currentBuild.getRawBuild().getExecutor().interrupt(Result.SUCCESS)
                        sleep(1)
                    }
                    NETBOX_HOST = [
                        "name": "netbox_host",
                        "host": destination_ip_address,
                        "allowAnyHosts": true,
                        "user": SSH_CREDENTIALS_USR,
                        "password": SSH_CREDENTIALS_PSW,
                        // "identityFile": env.SSH_CREDENTIALS_USER_CREDENTIALS,
                        "timeoutSec": 160,
                        "retryCount": 3,
                        "retryWaitSec": 5,
                        "pty": true
                    ]

                    // sh(script: "\$(echo '$gitlab_certificate' > ./test.txt) 2>/dev/null", returnStdout: true)
                    // println(SSH_CREDENTIALS_PSW)
                    // println("SSH_CREDENTIALS_PSW: '$SSH_CREDENTIALS_PSW'")
                    println("[INFO]: host script parameters loaded!")
                    println("[INFO]: using user: ${env.SSH_CREDENTIALS_USR} on target!")
                    // TARGET_REPOS_DIR = "${TARGET_REPOS_DIR}/${env.SSH_CREDENTIALS_USR}/containers"
                    println("deployment directory is: ${TARGET_REPOS_DIR}")
                    /////////////////////////////////////////////////////////////////////////
                }
            }
        }
        // export CLIENT_ID=$ECO_CREDENTIALS_USR
        // export CLIENT_SECRET='$ECO_CREDENTIALS_PSW'

        stage ("Provision host with netbox-deployment pre-requisites") {
            when {
                expression { params.PROVISION_HOST == 'true' }
            }
            steps {
                script{
                    //////////////////////////////////////////////////////////////////////////////////////
                    println("[info] Started creating netbox uid/gid on netbox host")
                    group_exists = sshCommand remote: NETBOX_HOST, command: 'echo $(getent group netbox)'
                    println("group_exists: '$group_exists'")

                    user_exists = sshCommand remote: NETBOX_HOST, command: 'echo $(id netbox)'
                    println("user_exists: '$user_exists'")

                    if (!user_exists || !group_exists ) {
                        if (!group_exists){
                            println("[INFO] creating netbox group on target host")
                            groupadd_output = sshCommand remote: NETBOX_HOST, sudo: true, command: 'groupadd netbox'
                            println("groupadd_output: $groupadd_output")
                        }
                        if (!user_exists){
                            println("[INFO] creating netbox user on target host")
                            sudo_output = sshCommand remote: NETBOX_HOST, sudo: true, command: 'useradd -m -s /bin/bash -g netbox -G docker netbox'
                            println("sudo_outputuser: '$sudo_output'")
                        }
                    }
                    println("[info] Finished creating netbox uid/gid on netbox host")
                    //////////////////////////////////////////////////////////////////////////////////////
                    //////////////////////////////////////////////////////////////////////////////////////
                    println("[info] exporting gitlab ssh certificate to netbox host")
                    gitlab_certificate = sh(script: "cat $GIT_CREDENTIALS", returnStdout: true)
                    writeFile file: "gitlab-cert", text: gitlab_certificate

                    sshPut remote: NETBOX_HOST, from: "gitlab-cert", into: "/tmp/id_rsa"

                    execute_command = """
                        umask 0
                        sudo chmod 600 /tmp/id_rsa
                        sudo chown netbox:netbox /tmp/id_rsa
                        sudo mv /tmp/id_rsa /home/netbox/.ssh/
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    println("[info] finished exporting gitlab ssh certificate to netbox host")
                    //////////////////////////////////////////////////////////////////////////////////////
                }
            }
        }

        stage ("Resetting netbox-docker on target for redeployment") {
            steps {
                script{
                    /////////////////////////////////////////////////////////////////////////
                    execute_command = """
                        sudo rm -rf /tmp/netbox-config/
                        sudo su netbox -c '''
                            if [ -d "${TARGET_REPOS_DIR}/netbox-docker" ]; then
                                echo "[INFO] netbox found! shutting down netbox-docker"
                                cd ${TARGET_REPOS_DIR}/netbox-docker
                                2>/dev/null $COMPOSER down -v
                                echo "[INFO]: Finished shutting down netbox-docker"
                            fi
                        '''
                    """
                    try {
                        reset_output = sshCommand remote: NETBOX_HOST, command: execute_command
                        println("$reset_output")
                    } catch (Exception e) {
                        error("[ERROR]: Failed to reset netbox on target: $e")
                    }
                    /////////////////////////////////////////////////////////////////////////
                }
            }
        }

        stage ("cleaning up old netbox-docker on target") {
            steps {
                script{
                    /////////////////////////////////////////////////////////////////////////
                    //
                    execute_command = """
                        sudo su root -c '''
                            if [ -d "${TARGET_REPOS_DIR}/netbox-docker" ]; then
                                rm -rf ${TARGET_REPOS_DIR}/netbox-docker
                                echo "[INFO]: Finished deleting netbox-docker";
                            else
                                echo "[INFO] netbox is not deployed! skipping reset...";
                            fi;
                        '''
                    """
                    try {
                        reset_output = sshCommand remote: NETBOX_HOST, command: execute_command
                        println("$reset_output")
                    } catch (Exception e) {
                        error("[ERROR]: Failed to reset netbox on target: $e")
                    }
                    /////////////////////////////////////////////////////////////////////////
                }
            }
        }

        stage ("Cloning netbox-docker on target") {
            steps {
                script{
                    /////////////////////////////////////////////////////////////////////////
                    println("[INFO]: cloning netbox-docker version: $netbox_docker_version to target at: '${TARGET_REPOS_DIR}'")
                    execute_command = """
                        sudo su netbox -c '''
                            2>/dev/null ssh-keyscan -p 2222 local-git.local >> ~/.ssh/known_hosts
                            mkdir -p ${TARGET_REPOS_DIR}
                            umask 022
                            cd ${TARGET_REPOS_DIR}
                            2>/dev/null git clone --recurse-submodules ${remote_netbox_docker_repo} -b $netbox_docker_version
                        '''
                    """
                    // try {
                        sshCommand remote: NETBOX_HOST, command: execute_command
                    // } catch (Exception e) {
                    //     error("[ERROR]: Failed to clone netbox-docker repo on target: $e")
                    // }
                    println("[INFO]: Finished cloning netbox-docker version: $netbox_docker_version to target at: '${TARGET_REPOS_DIR}'")
                    ///////////////////////////////////////////////////////////////////////////
                }
            }
        }

        stage ("Merging netbox-config into netbox-docker on target") {
            steps {
                script{
                    /////////////////////////////////////////////////////////////////////////
                    println("[INFO] collecting configuration from netbox-config")
                    execute_command = """
                        mkdir -p /tmp/netbox-config
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    sshPut remote: NETBOX_HOST, from: "./docker-compose.override.yml", into: "/tmp/netbox-config/"
                    sshPut remote: NETBOX_HOST, from: "./env/", into: "/tmp/netbox-config/"
                    sshPut remote: NETBOX_HOST, from: "./backups/", into: "/tmp/netbox-config/"
                    sshPut remote: NETBOX_HOST, from: "./backup_db.sh", into: "/tmp/netbox-config/"
                    sshPut remote: NETBOX_HOST, from: "./migrate_db.sh", into: "/tmp/netbox-config/"
                    sshPut remote: NETBOX_HOST, from: "./volumes/", into: "/tmp/netbox-config/"
                    println("[INFO] finished collecting configuration from netbox-config")

                    /////////////////////////////////////////////////////////////////////////
                    println("[INFO] pushing netbox configuration to target host")
                    execute_command = """
                        sudo cp -r /tmp/netbox-config/* ${TARGET_REPOS_DIR}/netbox-docker/
                        sudo chown -R netbox:netbox ${TARGET_REPOS_DIR}/netbox-docker/
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    println("[INFO] Finished pushing netbox configuration to target host")

                    /////////////////////////////////////////////////////////////////////////
                    println("setting up env file with envsubst")
                    execute_command = """
                        sudo su netbox -c '''
                            cd ${TARGET_REPOS_DIR}/netbox-docker/
                            export CLIENT_ID=$ECO_CREDENTIALS_USR
                            export CLIENT_SECRET=$ECO_CREDENTIALS_PSW
                            envsubst < ./env/netbox.env > ./env/tmp.env
                            mv ./env/tmp.env ./env/netbox.env
                            chmod 644 ./env/netbox.env

                        '''
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    /////////////////////////////////////////////////////////////////////////
                    // this needs to be parameterised later on.
                    /////////////////////////////////////////////////////////////////////////
                    println("[INFO] changing dockerhub repository")
                    execute_command = """
                        sudo su netbox -c '''
                            cd ${TARGET_REPOS_DIR}/netbox-docker/
                            sed -i "s/image: docker.io/image: $REGISTRY_URL/g" docker-compose.yml
                        '''
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    println("[INFO] Finished changing dockerhub repository")
                    /////////////////////////////////////////////////////////////////////////
                    println("[INFO] pulling images from target repos")
                    execute_command = """
                        sudo su netbox -c '''
                            cd ${TARGET_REPOS_DIR}/netbox-docker/
                            2>/dev/null echo '${REGISTRY_CREDENTIALS_PSW}' | docker login $REGISTRY_URL -u $REGISTRY_CREDENTIALS_USR --password-stdin
                            export VERSION=$netbox_version
                            2>/dev/null $COMPOSER pull 1>/dev/null
                        '''
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    println("[INFO] Finished pulling images from target repos")
                    /////////////////////////////////////////////////////////////////////////
                    // temporary workaround:
                    // adding read write permissions to deployment dir full path:
                    println("[INFO] Started setting up permissions for backups and compose files.")
                    execute_command = """
                        backup_path=${TARGET_REPOS_DIR}/netbox-docker/backups
                        while [[ -n \$backup_path ]]; do
                            echo "[INFO] chmod of path: '\$backup_path'"
                            sudo chmod o+rx \$backup_path
                            backup_path=\${backup_path%/*}
                        done
                        sudo chmod o+r ${TARGET_REPOS_DIR}/netbox-docker/backups/*.gz

                        sudo chown -R netbox:netbox ${TARGET_REPOS_DIR}/netbox-docker/volumes

                        sudo chown -R 999:0 ${TARGET_REPOS_DIR}/netbox-docker/volumes/netbox
                        sudo chmod o+r ${TARGET_REPOS_DIR}/netbox-docker/volumes/netbox/

                        sudo chmod 666 ${TARGET_REPOS_DIR}/netbox-docker/docker-compose.yml
                        sudo chmod 666 ${TARGET_REPOS_DIR}/netbox-docker/docker-compose.override.yml
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    println("[INFO] Finished setting up permissions for backups and compose files.")
                   /////////////////////////////////////////////////////////////////////////
                    println("[INFO] Started setting up permissions for volumes")
                    execute_command = """
                        volumes_path=${TARGET_REPOS_DIR}/netbox-docker/volumes/netbox
                        while [[ -n \$volumes_path ]]; do
                            echo "[INFO] chmod of path: '\$volumes_path'"
                            sudo chmod o+rx \$volumes_path
                            volumes_path=\${volumes_path%/*}
                        done
                    """
                    sshCommand remote: NETBOX_HOST, command: execute_command
                    println("[INFO] Finished setting up permissions for volumes")
                    /////////////////////////////////////////////////////////////////////////
                }
            }
        }



        stage ("starting netbox service") {
            steps {
                script{
                    /////////////////////////////////////////////////////////////////////////
                    println("[INFO] starting netbox on target host")
                    execute_command = """
                        sudo su netbox -c '''
                            docker network create netbox-external-con --subnet $NETBOX_SUBNET 2>/dev/null
                            cd ${TARGET_REPOS_DIR}/netbox-docker
                            export VERSION=$netbox_version
                            2>/dev/null $COMPOSER up -d
                        '''
                    """
                    try {
                        sshCommand remote: NETBOX_HOST, command: execute_command
                    } catch (Exception e) {
                        println("[WARNING]: finished deploying the netbox container, some errors might have occured...")
                    }
                    println("[INFO] Finished starting netbox service on target host")
                    /////////////////////////////////////////////////////////////////////////
                    println("[INFO] started sleeping for '$POST_DEPLOY_SLEEP_TIMER' seconds for netbox to start")
                    sleep(time: POST_DEPLOY_SLEEP_TIMER, unit:"SECONDS")
                    println("[INFO] finished sleeping for '$POST_DEPLOY_SLEEP_TIMER' seconds for netbox to start")

                }
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        stage ("Migrating netbox database") {
            steps {
                script{
                    ///////////////////////////////////////////////////////////////////////////
                    println("[INFO] starting migrating netbox database on target host")
                    execute_command = """
                        sudo su netbox -c '''
                            cd ${TARGET_REPOS_DIR}/netbox-docker
                            export COMPOSER=\"$COMPOSER\"
                            export DB_TO_RESTORE=$DB_TO_RESTORE
                            source migrate_db.sh
                        '''
                    """
                    try {
                        db_migration_result = sshCommand remote: NETBOX_HOST, command: execute_command
                        println("$db_migration_result")
                    } catch (Exception e) {
                        error("[ERROR]: Failed to migrate DB netbox on target:\n$e")
                    }
                    ///////////////////////////////////////////////////////////////////////////
                    println("[INFO] starting migrating netbox database on target host")
                    execute_command = """
                        docker exec -ti -u root netbox-docker-netbox-1 bash -c '''
                            source ../venv/bin/activate
                            pip install -i http://local-registry.local/artifactory/api/pypi/pypi/simple --trusted-host local-registry.local pyats[full]==23.11
                            pip install -i http://local-registry.local/artifactory/api/pypi/pypi/simple --trusted-host local-registry.local manuf==1.1.5
                            pip install -i http://local-registry.local/artifactory/api/pypi/pypi/simple --trusted-host local-registry.local xmltodict==0.13.0
                        '''
                    """
                    try {
                        sshCommand remote: NETBOX_HOST, command: execute_command
                    } catch (Exception e) {
                        println("[WARNING] failed to install pyats package on 'netbox-docker-netbox-1'.\n$e")
                    }
                    ///////////////////////////////////////////////////////////////////////////
                    execute_command = """
                        docker exec -ti -u root netbox-docker-worker-1 bash -c '''
                            source ../venv/bin/activate
                            pip install -i http://local-registry.local/artifactory/api/pypi/pypi/simple --trusted-host local-registry.local pyats[full]==23.11
                            pip install -i http://local-registry.local/artifactory/api/pypi/pypi/simple --trusted-host local-registry.local manuf==1.1.5
                            pip install -i http://local-registry.local/artifactory/api/pypi/pypi/simple --trusted-host local-registry.local xmltodict==0.13.0
                        '''
                    """
                    try {
                        sshCommand remote: NETBOX_HOST, command: execute_command
                    } catch (Exception e) {
                        println("[WARNING] failed to install pyats package on 'netbox-docker-worker-1'.\n$e")
                    }
                    ///////////////////////////////////////////////////////////////////////////
                }
            }
        }
    }
}

