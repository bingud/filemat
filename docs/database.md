
# Database details

Spring Data adapter for SQLite: `org.komamitsu:spring-data-sqlite`

Data file location: `/var/lib/filemat`


## Schema

The actual SQL schema is defined in [the Spring resources folder](</server/src/main/resources/sqlite-schema.sql>).


> PK: Primary Key  
UQ: Unique  
FK: Foreign Key  
NULLABLE: Field can be null  

#### `users` table
Column | type | attributes | detail
--- | --- | --- | ---
user_id | text | PK |  
email | text | UQ |  
username | text | UQ |  
password | text | |  
mfa_totp_secret | text | NULLABLE |  
mfa_totp_status | integer | |  
mfa_totp_codes | text | NULLABLE |  
mfa_totp_required | integer | |  
created_date | integer | |  
last_login_date | integer | NULLABLE |  
is_banned | integer | |  

#### `role` table
Column | type | attributes | detail
--- | --- | --- | ---
role_id | text | PK |  
name | text | |  
created_date | integer | |  
permissions | text | | JSON int array for system permission enums

#### `permissions` table
Column | type | attributes | detail
--- | --- | --- | ---
permission_id | text | PK |  
permission_type | integer | |  
entity_id | text | FK |  
user_id | text | FK, NULLABLE |  
role_id | text | FK, NULLABLE |  
permissions | text | | JSON int array for file permission enums
created_date | integer | |  

#### `files` table
Column | type | attributes | detail
--- | --- | --- | ---
entity_id | text | PK |  
path | text | UQ, NULLABLE |  
inode | integer | UQ, NULLABLE |  
is_filesystem_supported | integer | |  
owner_user_id | text | FK, NULLABLE |  

#### `auth_token` table
Column | type | attributes | detail
--- | --- | --- | ---
auth_token | text | PK |  
user_id | text | FK |  
created_date | integer | |  
user_agent | text | |  
max_age | integer | |  

#### `log` table
Column | type | attributes | detail
--- | --- | --- | ---
log_id | integer | PK |  
level | integer | |  
type | integer | |  
action | integer | |  
created_date | integer | |  
description | text | |  
message | text | |  
initiator_user_id | text | FK, NULLABLE |  
initiator_ip | text | NULLABLE |  
target_id | text | NULLABLE |  
metadata | text | NULLABLE | JSON object

#### `settings` table
Column | type | attributes | detail
--- | --- | --- | ---
name | text | PK |  
value | text | |  
created_date | integer | |  

#### `shared_files` table
Column | type | attributes | detail
--- | --- | --- | ---
share_id | text | PK |  
file_id | text | FK |  
user_id | text | FK |  
created_date | integer | |  
max_age | integer | |  
is_password | integer | |  
password | text | NULLABLE |  

#### `user_roles` table
Column | type | attributes | detail
--- | --- | --- | ---
role_id | text | PK, FK |  
user_id | text | PK, FK |  
created_date | integer | |  

#### `folder_visibility` table
Column | type | attributes | detail
--- | --- | --- | ---
path | text | PK |  
is_exposed | integer | |  
created_date | integer | |  

#### `saved_files` table
Column | type | attributes | detail
--- | --- | --- | ---
user_id | text | PK |  
path | text | PK |  
created_date | integer | |  
