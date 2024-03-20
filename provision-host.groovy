
// def workspace_path = ""

def node_name = 'node-1	'

def local_repos_dir = "/opt/automation/local-repos"
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

        string(
            name: 'docker_compose_version',
            description: 'docker-compose version to be provision host with for containers deployments.',
            defaultValue: "1.29.2"
        )

        string(
            name: 'destination_ip_address',
            description: 'IP address of the netbox host',
            defaultValue: "10.60.11.170"
        )

        choice(
            name: 'ssh_credentials',
            choices: ['svc_automation', 'jenkins-sdn.gen'],
            description: 'crendentatials ID of the username/private-key to be used to ssh to target.'
        )
    }

    environment {
        SSH_CREDENTIALS = credentials("${ssh_credentials}")
    }

    stages {
        stage ("Initialising variables") {
            steps {
                script{
                    host_ssh_script = [
                        "name": "netbox_host",
                        "host": destination_ip_address,
                        "allowAnyHosts": true,
                        "user": env.SSH_CREDENTIALS_USR,
                        "identityFile": env.SSH_CREDENTIALS,
                        "timeoutSec": 160,
                        "retryCount": 3,
                        "retryWaitSec": 5
                    ]
                    echo "[INFO]: host script parameters loaded!"
                    echo "[INFO]: using user: ${env.SSH_CREDENTIALS_USR} on target!"
                }
            }
        }

        stage ("prepare target vm for deployment") {
            // make sure that Dir, docker-compose etc exits and installed
            steps {
                script{
                    ///////////////////////////////////////////////////////////////////////////
                    // Testing connection to target host
                    execute_command = """
                        echo "Testing ssh connection to Host"
                                             """
                    try {
                        result = sshCommand remote: host_ssh_script, command: execute_command
                        echo "${result}"
                    } catch (Exception e) {
                        error("[ERROR]: Failed to connect to target VM: $e")
                    }
                    ///////////////////////////////////////////////////////////////////////////
                    execute_script = """
                                      echo "Ensuring that deployment workdir exists."
                                      sudo mkdir -p /opt/automation && sudo chown ${env.SSH_CREDENTIALS_USR}:${env.SSH_CREDENTIALS_USR} /opt/automation
                                      mkdir -p ${local_repos_dir}
                                      mkdir -p /opt/automation/docker
                                      """
                    try {
                        writeFile file: 'create_workdir.sh', text: execute_script
                        result = sshScript remote: host_ssh_script, script: "create_workdir.sh", sudo: true
                        echo "${result}"
                    } catch (Exception e) {
                        error("[ERROR]: Failed to ensure that workdir on target VM exists: $e")
                    }
                    ///////////////////////////////////////////////////////////////////////////
                    // ip link add name docker0 type bridge
                    // ip addr add dev docker0 172.17.0.1/16
                    execute_script = """
                                      echo "Ensuring that pre-requisite packages have been installed."
                                      sudo dnf update -y
                                      sudo dnf install net-tools bind-utils iputils procps wget curl git -y
                                      sudo dnf config-manager --add-repo=https://download.docker.com/linux/centos/docker-ce.repo
                                      sudo dnf install docker-ce docker-ce-cli containerd.io -y
                                      sudo systemctl enable --now docker
                                      sudo usermod -aG docker ${env.SSH_CREDENTIALS_USR}
                                      """

                    try {
                        writeFile file: 'install-prerequistes.sh', text: execute_script
                        result = sshScript remote: host_ssh_script, script: "install-prerequistes.sh", sudo: true
                        echo "${result}"
                    } catch (Exception e) {
                        error("[ERROR]: Failed to create folders on target VM: $e\n")
                    }
                    ///////////////////////////////////////////////////////////////////////////
                    // installing docker-compose if not already installed
                    execute_command = """
                                        if [ -n "/usr/local/bin/docker-compose" ]; then
                                            echo true
                                        else
                                            echo false
                                        fi
                                      """
                    docker_compose_install_check = sshCommand remote: host_ssh_script, command: execute_command

                    if (docker_compose_install_check == "true") {
                        echo "[INFO]: skipping docker-compose installation...Already deployed."
                    }
                    else {
                        execute_command = """
                            echo "[INFO]: Installing docker-compose"
                            export DOCKER_COMPOSE_LINK=https://github.com/docker/compose/releases/download/${docker_compose_version}/docker-compose-\$(uname -s)-\$(uname -m)
                            echo "[INFO]: Download link is: \$DOCKER_COMPOSE_LINK"
                            sudo curl -L "\$DOCKER_COMPOSE_LINK" -o /usr/local/bin/docker-compose
                                          """
                        try {
                            result = sshCommand remote: host_ssh_script, command: execute_command, sudo: true
                            echo "$result"
                        } catch (Exception e) {
                            error("[ERROR]: Failed to install docker-compose on target VM: $e")
                        }
                        ///////////////////////////////////////////////////////////////////////////
                        execute_command = """
                            echo "[INFO]: Changing docker-compose ownership"
                            sudo chown ${env.SSH_CREDENTIALS_USR}:${env.SSH_CREDENTIALS_USR} /usr/local/bin/docker-compose
                            echo "[INFO]: Making docker-compose executable"
                            sudo chmod +x /usr/local/bin/docker-compose
                            echo "[INFO]: Making docker socket executable by all users"
                            sudo chmod 666 /var/run/docker.sock
                                                """
                        try {
                            result = sshCommand remote: host_ssh_script, command: execute_command, sudo: true
                            echo "${result}"
                        } catch (Exception e) {
                            error("[ERROR]: Failed to configure docker on target VM: $e")
                        }
                    }
                    ///////////////////////////////////////////////////////////////////////////
                }
            }
        }



    }

    // post{
    //     failure{
    //         script{
    //             currentBuild.result = error_message
    //             return
    //         }
    //     }
    // }
    // post {
    //     failure {
    //         script{
    //             echo "send mail or something"
    //         }
    //     }

    //     cleanup {
    //         always {
    //             // workspace cleanup
    //             deleteDir()

    //             // temporary dirs cleanup
    //             dir("${workspace}@*") {
    //                 deleteDir()
    //             }
    //         }
    //     }
    // }
}