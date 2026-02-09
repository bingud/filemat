## Contents

[What is Filemat?](<#what-is-filemat> "What is Filemat?")  
[Features](<#features> "Features")  
[Requirements](<#requirements> "Requirements")  
[Installation](<#installation> "Installation")  
[Configuration](<#configuration> "Configuration")  
[Usage recommendations](<#usage-recommendations> "Usage recommendations")  
[Technical details](<#technical-details> "Technical details")  


## What is Filemat?

Filemat is a web-based file manager.  
It's self-hosted and allows you to manage your local files from anywhere using a web UI.  

Similar to other alternatives you might know: Nextcloud Files, FileBrowser.org.

<img src="/docs/images/screenshot_files_1.png" width="500" alt="Files screenshot" />

## Features

- User management, roles
- Granular permissions for users and roles
    - File permissions
    - Application administrative permissions
- File sharing with public link (optional password)
- Text file editing, video streaming

[See full list of features here](/docs/details.md)

## Requirements

- Linux
- Java 17
- (Or just Docker)


## Installation

You can run the JAR file directly, or use the Docker image ([https://hub.docker.com/r/bingud/filemat](<https://hub.docker.com/r/bingud/filemat> "https://hub.docker.com/r/bingud/filemat")).  
Filemat is currently not available on `apt`.  


### \- Running the Docker image
[See how to deploy with Docker](</docs/docker-deployment.md>)


### \- Running normally

Make sure your machine has Linux and Java 17 installed.  
Download the JAR file from the releases section.  
  
Use this command to run the file with Java (change the filename according to the JAR file you downloaded):  
`java -jar filemat.jar`  


## Configuration

### \- Environment variables

Input multiple values by putting a colon in between (`/one:/two`)

| Name                                                                 | Description                                                          | Default value                                                        | Example value                                                        |
| -------------------------------------------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------- |
| FM\_HIDDEN\_FOLDER\_PATHS                                            | List of folder paths to fully exclude and block.                     |                                                                      | /root:/home/folder                                                   |
| FM\_HIDE\_SENSITIVE\_FOLDERS                                         | Whether to hide sensitive Linux folders (like /root, .ssh)           | true                                                                 | false                                                                |
| FM\_NON\_SENSITIVE\_FOLDERS                                          | List of folders to exclude from sensitive folder list                |                                                                      | /root:/etc/ssh                                                       |
| FM\_FOLLOW\_SYMBOLIC\_LINKS                                          | Whether to follow symbolic links, or show them as normal files       | false                                                                | true                                                                 |
| FM\_FORCE\_DELETABLE\_FOLDERS                                        | List of protected system folders to be made deletable                |                                                                      | /root:/etc                                                           |
| FM\_ALLOW\_READ\_DATA\_FOLDER                                        | Allow the application data folder to be accessed                     | false                                                                | true                                                                 |
| FM\_ALLOW\_WRITE\_DATA\_FOLDER                                       | Allow the application data folder to be modified                     | false                                                                | true                                                                 |
| FM\_PRINT\_LOGS                                                      | Whether to print logs to console                                     | true                                                                 | false                                                                |
| FM\_DEV\_MODE                                                        | Whether Filemat is in dev mode                                       | false                                                                | true                                                                 |


## Usage recommendations

- **Run Filemat as non-root**<br>
  Create a dedicated linux user for Filemat. Create a group and add the Filemat user. Also add your other users that should keep access to the file.<br>
  Set this group (and group permissions) for all files that Filemat should manage.<br>
  It's possible to run Filemat as root — the most sensitive files are fully blocked by default — but it's discouraged for security reasons.
  
- **Limit file access**<br>
  If running Filemat with Docker, choose specific folders that Filemat should be able to access,<br>
  instead of exposing your entire filesystem through a Docker volume.<br>
  It's easy to configure volumes later with Docker compose.

## Technical details

- **Backend:** Spring Boot MVC (Kotlin)
- **Frontend:** SvelteKit (Svelte 5), statically built and served as static files by the backend
- **Database:** SQLite ([More details here](</docs/database.md>))

#### How files are exposed / secured
Files must be explicitly configured to be exposed, so that Filemat can allow users to interact with them.  
The application data folder is fully blocked by default, and can only be made accessible using an environment variable.  
Symbolic links can be toggled.  

In order to make any of these changes through the web UI, a user must have CLI access to the Filemat deployment to authenticate.  
Environment variables override these settings.  

## Contributing

Bug reports and feature requests are welcome in the GitHub Issues.  
For larger changes, please open an issue first to discuss what you want to add or change.
