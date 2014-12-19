/**
 * Copyright (C) 2014 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.ingestion.sink.mongodb;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import com.mongodb.util.JSONParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.flume.Event;
import org.bson.types.ObjectId;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.*;

class EventParser {

    private static final Logger log = LoggerFactory.getLogger(EventParser.class);

    private static final String DEFAULT_BINARY_ENCODING = "base64";

    private final MappingDefinition definition;

    public EventParser() {
        this(new MappingDefinition());
    }

    public EventParser(final MappingDefinition mappingDefinition) {
        this.definition = mappingDefinition;
    }

    public Object parseValue(final FieldDefinition fd, final String stringValue) {
        if (fd == null || fd.getType() == null) {
            try {
                return JSON.parse(stringValue);
            } catch (JSONParseException ex) {
                //XXX: Default to String
                log.trace("Could not parse as JSON, defaulting to String: {}", stringValue);
                return stringValue;
            }
        }
        switch (fd.getType()) {
            case DOUBLE:
                return Double.parseDouble(stringValue);
            case STRING:
                return stringValue;
            case OBJECT:
            case ARRAY:
                //TODO: should we use customizable array representation?
                //TODO: should we check that the result is indeed an array or object?
                return JSON.parse(stringValue);
            case BINARY:
                final String encoding = (fd.getEncoding() == null) ? DEFAULT_BINARY_ENCODING : fd.getEncoding().toLowerCase(Locale.ENGLISH);
                if ("base64".equals(encoding)) {
                    return BaseEncoding.base64().decode(stringValue);
                } else {
                    throw new UnsupportedOperationException("Unsupported encoding for binary type: " + encoding);
                }
                //TODO: case "UNDEFINED":
            case OBJECTID:
                return new ObjectId(stringValue);
            case BOOLEAN:
                return Boolean.parseBoolean(stringValue);
            case DATE:
                DateFormat dateFormat = fd.getDateFormat();
                if (dateFormat == null) {
                    if (StringUtils.isNumeric(stringValue)) {
                        return new Date(Long.parseLong(stringValue));
                    } else {
                        return ISODateTimeFormat.dateOptionalTimeParser().parseDateTime(stringValue).toDate();
                    }
                } else {
                    try {
                        return dateFormat.parse(stringValue);
                    } catch (ParseException ex) {
                        //XXX: Default to string
                        log.warn("Could not parse date, defaulting to String: {}", stringValue);
                        return stringValue;
                    }
                }
            case NULL:
                //TODO: Check if this is valid
                return null;
            //TODO: case "REGEX":
            //TODO: case "JAVASCRIPT":
            //TODO: case "SYMBOL":
            //TODO: case "JAVASCRIPT_SCOPE":
            case INT32:
                return Integer.parseInt(stringValue);
            case INT64:
                return Long.parseLong(stringValue);
            case GEO:
                return populateGeoObject(stringValue);
            case DOCUMENT:
                return populateDocument(fd,stringValue);
            default:
                throw new UnsupportedOperationException("Unsupported type: " + fd.getType().name());
        }
    }

    public DBObject parse(Event event) {

        DBObject dbObject = new BasicDBObject();
        if (definition.getBodyType() != MongoDataType.NULL) {
            Object obj = null;
            if (definition.getBodyType() == MongoDataType.BINARY && definition.getBodyEncoding().equals("raw")) {
                obj = event.getBody();
            } else if (definition.getBodyType() == MongoDataType.STRING) {
                Charset charset = Charset.forName(definition.getBodyEncoding());
                obj = new String(event.getBody(), charset);
            } else {
                FieldDefinition fd = new FieldDefinition();
                fd.setType(definition.getBodyType());
                fd.setEncoding(definition.getBodyEncoding());
                obj = parseValue(fd, new String(event.getBody(), Charsets.UTF_8));
            }

            if (!"".equals(definition.getBodyField())) {
                dbObject.put(definition.getBodyField(), obj);
            } else if (obj instanceof DBObject) {
                dbObject = (DBObject) obj;
            } else {
                log.warn("Could not map body to JSON document: {}", obj);
            }
        }

        final Map<String, String> eventHeaders = event.getHeaders();
        if (definition.allowsAdditionalProperties()) {
            for (final Map.Entry<String, String> headerEntry : eventHeaders.entrySet()) {
                final String fieldName = headerEntry.getKey();
                final String fieldValue = headerEntry.getValue();
                FieldDefinition def = definition.getFieldDefinitionByName(fieldName);
                if (def == null) {
                    dbObject.put(fieldName, parseValue(null, fieldValue));
                } else {
                    final String mappedName = (def.getMappedName() == null) ? def.getFieldName() : def.getMappedName();
                    if (eventHeaders.containsKey(fieldName)) {
                        dbObject.put(mappedName, parseValue(def, fieldValue));
                    }
                }
            }
        } else {
            for (FieldDefinition def : definition.getFields()) {
                final String fieldName = def.getFieldName();
                final String mappedName = (def.getMappedName() == null) ? def.getFieldName() : def.getMappedName();
                if (eventHeaders.containsKey(fieldName)) {
                    dbObject.put(mappedName, parseValue(def, eventHeaders.get(fieldName)));
                }
            }
        }

        return dbObject;
    }

    public List<DBObject> parse(List<Event> events) {
        List<DBObject> rows = new ArrayList<DBObject>(events.size());
        for (Event event : events) {
            rows.add(this.parse(event));
        }
        return rows;
    }

    private DBObject populateGeoObject(String loc) {
        DBObject geoLoc = new BasicDBObject();
        String[]locAsArray= loc.split("#");
        if (locAsArray.length==2){
            geoLoc.put("type", locAsArray[0]);
            geoLoc.put("loc", extractGeoLoc(locAsArray[1]));
        }
        return geoLoc;
    }

    private Double[] extractGeoLoc(String geoLoc) {
        String[] geoLocSplit = geoLoc.split(",");
        return new Double[]{Double.valueOf(geoLocSplit[0]),Double.valueOf(geoLocSplit[1])};
    }

    private DBObject populateDocument(FieldDefinition fd, String document) {
        DBObject dbObject = new BasicDBObject();
        String[]documentAsArrray= document.split("#");
//        if (documentAsArrray.length==2){

            Map<String, FieldDefinition> documentMapping = new LinkedHashMap<String, FieldDefinition>(fd.getDocumentMapping());
            int i=0;
            for (Map.Entry<String, FieldDefinition> documentField : documentMapping.entrySet()) {
                dbObject.put(documentField.getKey(), parseValue(documentField.getValue(), documentAsArrray[i++]));
            }
//        }

        return dbObject;
    }
}
