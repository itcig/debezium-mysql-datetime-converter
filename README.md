# Debezium MYSQL DateTime Converter  
Currently Debezium converts all of timestamp fields into UTC and sometimes we need local timestamp in upstream tools.

## Make Package
```
mvn package -Dmaven.test.skip=true
```

## Usage
in docker file, just copy library into mysql path  
```
COPY mysql-datetime-converter-1.0.0.jar /kafka/connect/debezium-connector-mysql/
```
### Configuration
```json
"converters": "mySqlDateTimeConverter",
"mySqlDateTimeConverter.type": "com.itcig.debezium.converter.MySqlDateTimeConverter"
"mySqlDateTimeConverter.timezone": "America/New_York"
```
