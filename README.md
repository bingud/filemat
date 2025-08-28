## Contents

[What is Filemat?](<#what-is-filemat> "What is Filemat?")  
[Features](<#features> "Features")  
[Requirements](<#requirements> "Requirements")  
[Installation](<#installation> "Installation")  
[Configuration](<#configuration> "Configuration")  
[How Filemat runs](<#how-filemat-runs> "How Filemat runs")  


## What is Filemat?

Filemat is a web-based file manager.  
It's self hosted, and it allows you to access files on your local machine through a web UI.  
  


## Features

- User accounts and roles
- Granular permissions
    - File and administration permissions for users and roles



## Requirements

- Linux
- Java 21



## Installation

You can run the JAR file directly, or use the Docker image ([https://hub.docker.com/r/bingud/filemat](<https://hub.docker.com/r/bingud/filemat> "https://hub.docker.com/r/bingud/filemat")).  
Filemat is currently not available on APT.  


### \- Running the Docker image

Make sure you have Docker installed. ([https://docs.docker.com/get-started/get-docker/](<https://docs.docker.com/get-started/get-docker/>))  
  
If using a Docker UI, run the container image called `bingud/filemat`, or use the commands below.  
Make sure to use a volume so that Filemat data persists outside of the container.  
The container is listening on port `8080`.  
  
Run the container using the `docker run` command:  
`docker run -d --name filemat -p 443:8080 -v /srv/filemat-data:/var/lib/filemat --restart unless-stopped bingud/filemat`  
  
You can change the display name, network port, docker volume location, environment variables and other properties this way:  
`docker run -d --name myName -p 5555:8080 -v /myHostFolder:/var/lib/filemat -e "MY_VARIABLE=myValue" --restart always bingud/filemat`  


### \- Running normally

Make sure your machine has Linux and Java 21 installed.  
Download the JAR file from the releases section.  
  
Use this command to run the file with Java (change the filename according to the JAR file you downloaded):  
`sudo java -jar filemat.jar`  


## Configuration

### \- Environment variables

Input multiple values by putting a colon in between (`/one:/two`)

| Name                                                                 | Description                                                          | Default value                                                        | Example value                                                        |
| -------------------------------------------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------- |
| FM\_HIDDEN\_FOLDER\_PATHS                                            | List of folder paths to fully exclude and block.                     |                                                                      | /root:/home/folder                                                   |
| FM\_HIDE\_SENSITIVE\_FOLDERS                                         | Whether to hide sensitive linux folders (like root, .ssh)            | true                                                                 | false                                                                |
| FM\_NON\_SENSITIVE\_FOLDERS                                          | List of folders to exlude from sensitive folder list                 |                                                                      | /root:/etc/ssh                                                       |
| FM\_FOLLOW\_SYMBOLIC\_LINKS                                          | Whether to follow symbolic links, or show them as normal files       | false                                                                | true                                                                 |
| FM\_FORCE\_DELETABLE\_FOLDERS                                        | List of protected system folders to be made deletable                |                                                                      | /root:/etc                                                           |
| FM\_ALLOW\_READ\_DATA\_FOLDER                                        | Allow the application data folder to be accessed                     | false                                                                | true                                                                 |
| FM\_ALLOW\_WRITE\_DATA\_FOLDER                                       | Allow the application data folder to be modified                     | false                                                                | true                                                                 |
| FM\_PRINT\_LOGS                                                      | Whether to print logs to console                                     | true                                                                 | false                                                                |
| FM\_DEV\_MODE                                                        | Whether Filemat is in dev mode                                       | false                                                                | true                                                                 |



## How filemat runs

- Filemat uses a single database file to function, so it's easy to move around and configure.
- It can run as root and allow you to configure file permissions yourself, without tampering with low-level filesystem permissions.