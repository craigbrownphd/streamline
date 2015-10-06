package com.hortonworks.bolt;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hortonworks.client.RestClient;
import com.hortonworks.iotas.catalog.DataSource;
import com.hortonworks.iotas.catalog.ParserInfo;
import com.hortonworks.iotas.common.IotasEvent;
import com.hortonworks.iotas.common.IotasEventImpl;
import com.hortonworks.iotas.model.IotasMessage;
import com.hortonworks.iotas.parser.Parser;
import com.hortonworks.topology.UnparsedTupleHandler;
import com.hortonworks.util.ReflectionHelper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class ParserBolt extends BaseRichBolt {
    private static final Logger LOG = LoggerFactory.getLogger(ParserBolt.class);
    public static final String CATALOG_ROOT_URL = "catalog.root.url";
    public static final String LOCAL_PARSER_JAR_PATH = "local.parser.jar.path";
    public static final String IOTAS_EVENT = "iotas.event";
    private OutputCollector collector;

    private RestClient client;
    private String localParserJarPath;
    private static ConcurrentHashMap<Object, Parser> parserMap = new ConcurrentHashMap<Object, Parser>();
    private static ConcurrentHashMap<Object, DataSource> dataSourceMap = new ConcurrentHashMap<Object, DataSource>();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private Long parserId;
    private Long dataSourceId;
    private UnparsedTupleHandler unparsedTupleHandler;

    /**
     * If user knows this instance of parserBolt is mapped to a topic which has messages that conforms to exactly one type of parser,
     * they can ignore the rest lookup to get parser based on deviceId and version and instead set a single parser via this method.
     *
     * @param parserId
     */
    public void withParserId(Long parserId) {
        this.parserId = parserId;
    }

    /**
     * If the user knows the dataSourceId they can set it here. This will be set in the IotasEvent as the default.
     *
     * @param dataSourceId
     */
    public void withDataSourceId(Long dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public ParserBolt withUnparsedTupleHandler (UnparsedTupleHandler unparsedTupleHandler) {
        this.unparsedTupleHandler = unparsedTupleHandler;
        return this;
    }


    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        if (!stormConf.containsKey(CATALOG_ROOT_URL) || !stormConf.containsKey(LOCAL_PARSER_JAR_PATH)) {
            throw new IllegalArgumentException("conf must contain " + CATALOG_ROOT_URL + " and " + LOCAL_PARSER_JAR_PATH);
        }
        String catalogRootURL = stormConf.get(CATALOG_ROOT_URL).toString();
        //We could also add the iotasMessage timestamp to calculate overall pipeline latency.
        this.collector = collector;
        this.localParserJarPath = stormConf.get(LOCAL_PARSER_JAR_PATH).toString();
        this.client = new RestClient(catalogRootURL);
        if (this.unparsedTupleHandler != null) {
            try {
                this.unparsedTupleHandler.prepare(stormConf);
            } catch (Exception ex) {
                throw new RuntimeException("Could not prepare " +
                        "UnparsedTupleHandler used to account for bad tuples.",
                        ex);
            }
        }
    }

    public void execute(Tuple input) {
        byte[] bytes = input.getBinaryByField("bytes");
        byte[] failedBytes = bytes;
        Parser parser = null;
        String messageId = null;
        try {
            if (parserId == null) {
                //If a parserId is not configured in parser Bolt, we assume the message has iotasMessage.
                IotasMessage iotasMessage = objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), IotasMessage.class);
                parser = getParser(iotasMessage);
                if(dataSourceId == null) {
                    dataSourceId = getDataSource(iotasMessage).getDataSourceId();
                }
                bytes = iotasMessage.getData();
                messageId = iotasMessage.getMessageId();
            } else {
                parser = getParser(parserId);
            }

            Map<String, Object> parsed = parser.parse(bytes);
            String dsrcId = dataSourceId == null ? StringUtils.EMPTY : dataSourceId.toString();
            IotasEvent event;
            /**
             * If message id is set in the incoming message, we use it as the IotasEvent id, else
             * the id is random UUID.
             */
            if(messageId == null) {
                event = new IotasEventImpl(parsed, dsrcId);
            } else {
                event = new IotasEventImpl(parsed, dsrcId, messageId);
            }
            Values values = new Values(event);
            collector.emit(input, values);
            collector.ack(input);
        } catch (Exception e) {
            LOG.warn("Failed to parse a tuple. Saving it using " +
                    "UnparsedTupleHandler implementation.", e);
            if (this.unparsedTupleHandler != null) {
                try {
                    this.unparsedTupleHandler.save(failedBytes);
                    collector.ack(input);
                } catch (Exception ex) {
                    LOG.error("Failed to save bad tuple using UnparsedTupleHandler", ex);
                    reportFailure(input, e);
                }
            } else {
                reportFailure(input, e);
            }
       }
    }

    private void reportFailure(Tuple input, Exception e) {
        collector.fail(input);
        collector.reportError(e);
        LOG.error("Failed to parse and save the bad tuple using " +
                "UnparsedTupleHandler.", e);
    }

    public void cleanup () {
        if (this.unparsedTupleHandler != null) {
            try {
                this.unparsedTupleHandler.cleanup();
            } catch (Exception ex) {
                LOG.warn("Could not cleanup UnparsedTupleHandler", ex);
            }
        }
    }


    private Parser loadParser(ParserInfo parserInfo) {
        InputStream parserJar = client.getParserJar(parserInfo.getParserId());
        String jarPath = String.format("%s%s-%s.jar", localParserJarPath, File.separator, parserInfo.getParserName());

        try {
            IOUtils.copy(parserJar, new FileOutputStream(new File(jarPath)));
            if (!ReflectionHelper.isClassLoaded(parserInfo.getClassName())) {
                ReflectionHelper.loadJarAndAllItsClasses(jarPath);
            }

            return ReflectionHelper.newInstance(parserInfo.getClassName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DataSource getDataSource(IotasMessage iotasMessage) {
        DataSourceIdentifier key = new DataSourceIdentifier(iotasMessage.getId(), iotasMessage.getVersion());
        DataSource dataSource = dataSourceMap.get(key);
        if(dataSource == null) {
            dataSource = client.getDataSource(key.getId(), Long.valueOf(key.getVersion()));
            DataSource existing = dataSourceMap.putIfAbsent(key, dataSource);
            if(existing != null) {
                dataSource = existing;
            }
        }
        return dataSource;
    }

    private Parser getParser(IotasMessage iotasMessage) {
        DataSourceIdentifier dataSourceId = new DataSourceIdentifier(iotasMessage.getId(), iotasMessage.getVersion());
        Parser parser = parserMap.get(dataSourceId);
        if (parser == null) {
            ParserInfo parserInfo = client.getParserInfo(dataSourceId.getId(), Long.valueOf(dataSourceId.getVersion()));
            parser = getParserAndOptionallyUpdateCache(parserInfo, dataSourceId);
        }
        return parser;
    }

    private Parser getParser(Long parserId) {
        Parser parser = parserMap.get(parserId);
        if (parser == null) {
            ParserInfo parserInfo = client.getParserInfo(parserId);
            parser = getParserAndOptionallyUpdateCache(parserInfo, parserId);
        }
        return parser;
    }

    private Parser getParserAndOptionallyUpdateCache(ParserInfo parserInfo, Object key) {
        Parser loadedParser = loadParser(parserInfo);
        Parser parser = parserMap.putIfAbsent(key, loadedParser);
        if (parser == null) {
            parser = loadedParser;
        }
        return parser;
    }



    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields(IOTAS_EVENT));
    }

    /**
     * Parser will always receive an IotasMessage, which will have id and version to uniquely identify the datasource
     * this message is associated with. This class is just a composite structure to represent that unique datasource identifier.
     */
    private static class DataSourceIdentifier {
        private String id;
        private Long version;

        private DataSourceIdentifier(String id, Long version) {
            this.id = id;
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public Long getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DataSourceIdentifier)) return false;

            DataSourceIdentifier that = (DataSourceIdentifier) o;

            if (id != null ? !id.equals(that.id) : that.id != null) return false;
            return !(version != null ? !version.equals(that.version) : that.version != null);

        }

        @Override
        public int hashCode() {
            int result = id != null ? id.hashCode() : 0;
            result = 31 * result + (version != null ? version.hashCode() : 0);
            return result;
        }
    }

    //in default scope so test can inject a mock instance, We could spin up an in process
    //rest server and configure it with all the correct catalog entries, but seems like an overkill for a test.
    void setClient(RestClient client) {
        this.client = client;
    }
}
