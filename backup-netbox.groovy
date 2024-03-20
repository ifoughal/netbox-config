// def workspace_path = ""

// def node_hostname = ""
// def node_ip_address = ""
def COMPOSER = "docker compose"

def node_name = 'node-1	'

def error_message = ''

def docker_compose_version = "2.21.0"

def LOCAL_REPOS_DIR = "/home/netbox/containers"

def BACKUP_LOCATION = "/home/ifoughal/repos/netbox-config/backups"
def NETBOX_DEPLOYED = true

def DEFAULT_GIT_BRANCH = "develop"
def BACKUP_GIT_BRANCH = "feat/backups"
// reprequisites:
// ssh_config:
//   Host git@local-git.local
//   HostName git@local-git.local
//   Port 2222
//   User git
//   IdentityFile ~/.ssh/config/git-key
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
            name: 'backup_is_git_repo',
            description: "if the backup is a repo, then push the changes to remote on branch: '$BACKUP_GIT_BRANCH'",
            defaultValue: true
        )
        ////////////////////////////////////////////////////////////
        string(
            name: 'netbox_host_address',
            description: 'IP address of the netbox host',
            defaultValue: "10.10.10.1"
        )
        ////////////////////////////////////////////////////////////
        string(
            name: 'backup_host_address',
            description: 'IP address of the backup server',
            defaultValue: "10.10.10.1"
        )
        ////////////////////////////////////////////////////////////
        choice(
            name: 'NETBOX_HOST_CREDENTIALS',
            choices: ['priviledged_user', 'jenkins-sdn.gen'],
            description: 'crendentatials ID of the username/private-key to be used to ssh to target.'
        )
        choice(
            name: 'BACKUP_HOST_CREDENTIALS',
            choices: ['priviledged_user'],
            description: 'crendentatials ID of the username/private-key to be used to ssh to target.'
        )
        ////////////////////////////////////////////////////////////
    }

    environment {
        NETBOX_HOST_CREDENTIALS = credentials("${NETBOX_HOST_CREDENTIALS}")
        BACKUP_HOST_CREDENTIALS = credentials("${BACKUP_HOST_CREDENTIALS}")

    }

    stages {
        stage ("Initialising pipeline") {
            steps {
                script{
                    NETBOX_HOST = [
                        "name": "netbox_host",
                        "host": netbox_host_address,
                        "allowAnyHosts": true,
                        "user": NETBOX_HOST_CREDENTIALS_USR,
                        "password": NETBOX_HOST_CREDENTIALS_PSW,
                        // "identityFile": env.NETBOX_HOST_CREDENTIALS_USER_CREDENTIALS,
                        "timeoutSec": 160,
                        "retryCount": 3,
                        "retryWaitSec": 5,
                        // "pty": true
                    ]

                    BACKUP_HOST = [
                        "name": "backup_host",
                        "host": backup_host_address,
                        "allowAnyHosts": true,
                        "user": BACKUP_HOST_CREDENTIALS_USR,
                        "password": BACKUP_HOST_CREDENTIALS_PSW,
                        // "identityFile": env.NETBOX_HOST_CREDENTIALS_USER_CREDENTIALS,
                        "timeoutSec": 160,
                        "retryCount": 3,
                        "retryWaitSec": 5,
                    ]
                    println("[INFO]: host script parameters loaded!")
                    println("[INFO]: using user: ${env.NETBOX_HOST_CREDENTIALS_USR} on target!")
                    println("deployment directory is: ${LOCAL_REPOS_DIR}")

                    NETBOX_DEPLOYED = 'true'
                    /////////////////////////////////////////////////////////////////////////
                }
            }
        }

        // stage ("checking if netbox is deployed") {
        //     steps {
        //         script{
        //             /////////////////////////////////////////////////////////////////////////
        //             // try {
        //                 echo "[INFO]: starting check if netbox is deployed"
        //                 execute_command = """
        //                     if [ -d "${LOCAL_REPOS_DIR}/netbox-docker" ]; then
        //                         NETBOX_DEPLOYED=true;
        //                     else
        //                         NETBOX_DEPLOYED=false;
        //                     fi
        //                     echo \$NETBOX_DEPLOYED
        //                 """
        //                 NETBOX_DEPLOYED = sshCommand remote: NETBOX_HOST, command: execute_command
        //                 echo "[INFO]: Finished check if netbox is deployed"

        //                 println("NETBOX_DEPLOYED: ${NETBOX_DEPLOYED}")
        //             // } catch (Exception e) {
        //             //     error("[ERROR]: Failed to reset netbox on target: $e")
        //             // }
        //             /////////////////////////////////////////////////////////////////////////
        //         }
        //     }
        // }

        stage ("backup netbox") {
            when {
                expression { NETBOX_DEPLOYED == 'true' }
            }
            steps {
                script{
                    // PTY enabled is necessary to get docker-compose stdout back to jenkins
                    NETBOX_HOST.pty = true
                    execute_command = """
                        sudo su netbox -c '''
                            cd ${LOCAL_REPOS_DIR}/netbox-docker
                            export COMPOSER=\"$COMPOSER\"
                            source backup_db.sh
                        '''
                    """
                    LATEST_BACKUP = sshCommand remote: NETBOX_HOST, command: execute_command
                    LATEST_BACKUP = LATEST_BACKUP.split("\n")[0]

                    println("[INFO] Finished netbox backup: '$LATEST_BACKUP'")
                }
            }
        }

        stage ("export netbox backups to backup location") {
            when {
                expression { NETBOX_DEPLOYED == 'true' }
            }
            steps {
                script{
                    println("[INFO] Started exporting netbox backup: '${LATEST_BACKUP}' to backup server: '$netbox_host_address' at: '${BACKUP_LOCATION}/${LATEST_BACKUP}'")

                    try {
                        sshGet remote: NETBOX_HOST, from: "${LOCAL_REPOS_DIR}/netbox-docker/backups/${LATEST_BACKUP}", into: "/tmp/latest_backup.sql.gz", override: true
                    } catch (Exception e) {
                        error("[ERROR]: failed to export netbox backup to tmp folder: $e")
                    }
                    try {
                        sshPut remote: BACKUP_HOST, from: "/tmp/latest_backup.sql.gz", into: "${BACKUP_LOCATION}/${LATEST_BACKUP}"
                    } catch (Exception e) {
                        // if permissions error occur:
                        // fix 1: append user to group
                        // fix 2: chmod o+rx to the whole path to dir:
                        //        eg: /home/user/backups then chmod o+rx must be applied to all these nested folders.
                        error("[ERROR]: failed to export netbox backup to backup server:\n$e")
                    }
                    println("[INFO] Finished exporting netbox backup: '${LATEST_BACKUP}' to backup server: '$netbox_host_address' at: '${BACKUP_LOCATION}/${LATEST_BACKUP}'")
                }
            }
        }



        stage ("export netbox backups to git") {
            when {
                expression { backup_is_git_repo == 'true' }
            }
            steps {
                script{
                    println("[INFO] Started pushing new backups to git repo")
                    execute_command = """
                            echo "[INFO] changing to backup location dir"
                            cd ${BACKUP_LOCATION}
                            cd ..
                            echo "[INFO] fetching and scm pulling from default branch: '$DEFAULT_GIT_BRANCH'"
                            git fetch -a 2>/dev/null
                            git checkout $DEFAULT_GIT_BRANCH 2>/dev/null
                            git branch --set-upstream-to=origin/$DEFAULT_GIT_BRANCH
                            echo "[INFO] Started scm pulling for default branch: '$DEFAULT_GIT_BRANCH'"
                            git pull 2>/dev/null
                            echo "[INFO] check out at backup branch: '$BACKUP_GIT_BRANCH'"
                            git checkout -b $BACKUP_GIT_BRANCH 2>/dev/null
                            git checkout $BACKUP_GIT_BRANCH 2>/dev/null
                            git branch --set-upstream-to=origin/$BACKUP_GIT_BRANCH 2>/dev/null
                            echo "[INFO] Started scm pulling for backup branch: '$BACKUP_GIT_BRANCH'"
                            git pull --ff-only 2>/dev/null
                            echo "[INFO] Started mergin default into backup branch"
                            git merge $DEFAULT_GIT_BRANCH --no-edit 2>/dev/null
                            git add backups && git commit -m "Feat: added new backup: $LATEST_BACKUP" && git push --set-upstream origin $BACKUP_GIT_BRANCH
                    """
                    push_to_repo = sshCommand remote: NETBOX_HOST, command: execute_command
                    println(push_to_repo)
                    println("[INFO] Finished pushing new backups to git repo")
                }
            }
        }


    }
}
