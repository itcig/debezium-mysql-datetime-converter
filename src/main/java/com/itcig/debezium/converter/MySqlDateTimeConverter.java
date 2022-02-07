package com.itcig.debezium.converter;

import io.debezium.spi.converter.CustomConverter;
import io.debezium.spi.converter.RelationalColumn;

import java.sql.Timestamp;
import java.time.*;
import java.util.function.Consumer;
import java.util.Properties;

import org.apache.kafka.connect.data.SchemaBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Problems dealing with Debezium time conversion
 * Debezium converts the datetime type in MySQL to a UTC timestamp by default
 * ({@link io.debezium.time.Timestamp}). The time zone is hard-coded and cannot
 * be changed.
 * Causes the UTC+4 set in the database to become a long timestamp of four hours
 * more in kafka
 * Debezium converts the timestamp type in MySQL to a UTC string by default
 *
 * | mysql                               | mysql-binlog-connector                   | debezium                          |
 * | ----------------------------------- | ---------------------------------------- | --------------------------------- |
 * | date<br>(2021-01-28)                | LocalDate<br/>(2021-01-28)               | Integer<br/>(18655)               |
 * | time<br/>(17:29:04)                 | Duration<br/>(PT17H29M4S)                | Long<br/>(62944000000)            |
 * | timestamp<br/>(2021-01-28 17:29:04) | ZonedDateTime<br/>(2021-01-28T09:29:04Z) | String<br/>(2021-01-28T09:29:04Z) |
 * | Datetime<br/>(2021-01-28 17:29:04)  | LocalDateTime<br/>(2021-01-28T17:29:04)  | Long<br/>(1611854944000)          |
 *
 * @see io.debezium.connector.mysql.converters.TinyIntOneToBooleanConverter
 */
public class MySqlDateTimeConverter implements CustomConverter<SchemaBuilder, RelationalColumn> {

    private static final Logger logger = LoggerFactory.getLogger(MySqlDateTimeConverter.class);

    private ZoneId timestampZoneId = ZoneId.systemDefault();

    @Override
    public void configure(Properties props) {
        readProps(props, "timezone", z -> timestampZoneId = ZoneId.of(z));

        logger.debug("Converter Timezone set to: {}", timestampZoneId);
    }

    private void readProps(Properties properties, String settingKey, Consumer<String> callback) {
        String settingValue = (String) properties.get(settingKey);
        if (settingValue == null || settingValue.length() == 0) {
            return;
        }
        try {
            callback.accept(settingValue.trim());
        } catch (IllegalArgumentException | DateTimeException e) {
            logger.error("The \"{}\" setting is illegal:{}", settingKey, settingValue);
            throw e;
        }
    }

    @Override
    public void converterFor(RelationalColumn column, ConverterRegistration<SchemaBuilder> registration) {

        SchemaBuilder schemaBuilder = null;
        Converter converter = null;

        String sqlTable = column.dataCollection().toUpperCase();
        String sqlField = column.name().toUpperCase();
        String sqlType = column.typeName().toUpperCase();

        logger.debug("Custom converter setting schema for ({}) {}.{}", sqlType, sqlTable, sqlField);

        if ("DATETIME".equals(sqlType)) {
            schemaBuilder = SchemaBuilder.int64().optional().name("io.debezium.time.Timestamp").version(1);

            converter = this::convertDateTime;
        }

        if ("TIMESTAMP".equals(sqlType)) {
            schemaBuilder = SchemaBuilder.string().optional().name("io.debezium.time.ZonedTimestamp").version(1);

            // TODO: Not working because somehow a field is being sent with `null` value even though
            // this field is required and has a default value.
            //
            // Set default timestamp to beginning epoch for required fields to match
            // Debezium behavior
            // if (column.hasDefaultValue()) {
            //     String sqlFieldDefault = ((String) column.defaultValue()).toUpperCase();
            //
            //     if (sqlFieldDefault.contains("CURRENT_TIMESTAMP") || sqlFieldDefault.contains("NOW")) {
            //         schemaBuilder = schemaBuilder.defaultValue("1970-01-01T00:00:00Z");
            //     }
            // }

            converter = this::convertTimestamp;
        }

        // TODO: Default Value field above is not working so set every field to optional
        // Set nullable fields in schema. If uncommented then remove the .optional() calls above.
        // NOTE: This could cause issues if a null value is parsed in Java for a
        // required field
        // if (column.isOptional()) {
        //     schemaBuilder = schemaBuilder.optional();
        // }

        if (schemaBuilder != null) {
            registration.register(schemaBuilder, converter);
            logger.info("Register custom converter for sqlType {} to schema {}", sqlType, schemaBuilder.name());
        }
    }

    /**
     * Datetimes are converted to UTC when ingested thereby advancing the time by
     * the GMT offset.
     * These can be converted to the local timezone (passed to this converter) to
     * return to the original
     * time in the source DB. To maintain consistency with the Debezium timestamp
     * schema, these are
     * returned as an Epoch in milliseconds.
     *
     * @param input
     * @return
     */
    private Long convertDateTime(Object input) {
        logger.debug("DATETIME value: {}", input);

        if (input != null) {
          logger.debug("DATETIME value is instance of: {}", input.getClass());

          ZonedDateTime zonedDateTime = null;

          // java.time.LocalDateTime is used when reading CDC data via connector
          if (input instanceof LocalDateTime) {
            zonedDateTime = ((LocalDateTime) input).atZone(timestampZoneId);

          }
          // java.sql.Timestamp is used during Debezium snapshots
          else if (input instanceof Timestamp) {
              zonedDateTime = ((Timestamp) input).toLocalDateTime().atZone(timestampZoneId);
          }

          if (zonedDateTime != null) {
            logger.debug("convertDateTime: {} -> {}", zonedDateTime, zonedDateTime.toInstant().toEpochMilli());

            return zonedDateTime.toInstant().toEpochMilli();
          }
        }

        return null;
    }

    /**
     * Timestamps are already converted to UTC based on the connector/server
     * timezone offset.
     * So they only need to be converted to ISO format with no GMT offset.
     *
     * @param input
     * @return
     */
    private String convertTimestamp(Object input) {
        logger.debug("TIMESTAMP value: {}", input);

        if (input != null) {
          logger.debug("TIMESTAMP value is instance of: {}", input.getClass());

          Instant timestampInstant = null;

          // java.time.ZonedDateTime is used when reading CDC data via connector
          if(input instanceof ZonedDateTime) {
            timestampInstant = ((ZonedDateTime) input).toInstant();
          }
          // java.sql.Timestamp is used during Debezium snapshots
          else if (input instanceof Timestamp) {
            timestampInstant = ((Timestamp) input).toInstant();
          }

          if (timestampInstant != null) {
            logger.debug("convertTimestamp: Instant {}", timestampInstant.toString());

            return timestampInstant.toString();
          }
        }

        return null;
    }
}
