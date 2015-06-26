/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.event.processor.core.internal.storm;

import backtype.storm.StormSubmitter;
import backtype.storm.generated.*;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.utils.NimbusClient;
import backtype.storm.utils.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift7.TException;
import org.json.simple.JSONValue;
import org.w3c.dom.Document;
import org.wso2.carbon.event.processor.core.ExecutionPlanConfiguration;
import org.wso2.carbon.event.processor.core.exception.ExecutionPlanConfigurationException;
import org.wso2.carbon.event.processor.core.exception.ServerUnavailableException;
import org.wso2.carbon.event.processor.core.exception.StormDeploymentException;
import org.wso2.carbon.event.processor.core.exception.StormQueryConstructionException;
import org.wso2.carbon.event.processor.core.internal.ds.EventProcessorValueHolder;
import org.wso2.carbon.event.processor.core.internal.storm.util.StormQueryPlanBuilder;
import org.wso2.carbon.event.processor.core.internal.storm.util.StormTopologyConstructor;
import org.wso2.carbon.utils.CarbonUtils;
import org.yaml.snakeyaml.Yaml;

import javax.xml.stream.XMLStreamException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Siddhi Topology Manager
 */
public class StormTopologyManager {

    private Map stormConfig;
    private String jarLocation;
    private static final Log log = LogFactory.getLog(StormTopologyManager.class);
    private final ConcurrentHashMap<String, TopologySubmitter> toDeployTopologies = new ConcurrentHashMap();
    private TopologyManagerThreadFactory topologyManagerThreadFactory = new TopologyManagerThreadFactory("Storm Deployment");

    public StormTopologyManager() {
        String stormConfigDirPath = CarbonUtils.getCarbonConfigDirPath() + File.separator + "cep" + File.separator + "storm";
        try {
            InputStream stormConf = new FileInputStream(new File(stormConfigDirPath + File.separator + "storm.yaml"));
            Yaml yaml = new Yaml();
            Map data = (Map) yaml.load(stormConf);
            if (data != null) {                          //Can be null for a commented out config
                stormConfig = Utils.readDefaultConfig();
                stormConfig.putAll(data);
            } else {
                stormConfig = Utils.readStormConfig();
            }
        } catch (FileNotFoundException e) {
            log.warn("Error occurred while reading storm configurations using default configurations", e);
        }

        jarLocation = stormConfigDirPath + File.separator + EventProcessorValueHolder.getStormDeploymentConfiguration().getJar();
    }

    public List<TopologySummary> getTopologies() throws StormDeploymentException {
        try {
            Nimbus.Client client = NimbusClient.getConfiguredClient(stormConfig).getClient();
            return client.getClusterInfo().get_topologies();
        } catch (TException e) {
            throw new StormDeploymentException("Cannot get topologies from storm cluster", e);
        }
    }

    public void submitTopology(ExecutionPlanConfiguration configuration, List<String> importStreams,
                               List<String> exportStreams, int tenantId, int resubmitRetryInterval) throws
            StormDeploymentException, ExecutionPlanConfigurationException {
        String executionPlanName = configuration.getName();
        TopologyBuilder builder;
        try {
            Document document = StormQueryPlanBuilder.constructStormQueryPlanXML(configuration, importStreams, exportStreams);
            String stormQueryPlan = getStringQueryPlan(document);
            if (log.isDebugEnabled()) {
                log.debug("Following is the generated Storm query plan for execution plan: " + configuration.getName() +
                        "\n" + stormQueryPlan);
            }
            builder = StormTopologyConstructor.constructTopologyBuilder(stormQueryPlan, executionPlanName, tenantId, EventProcessorValueHolder.getStormDeploymentConfiguration());
        } catch (XMLStreamException e) {
            throw new StormDeploymentException("Invalid Config for Execution Plan " + executionPlanName + " for tenant " + tenantId, e);
        } catch (TransformerException e) {
            throw new StormDeploymentException("Error while converting to storm query plan string. " +
                    "Execution plan: " + executionPlanName + " Tenant: " + tenantId, e);
        } catch (StormQueryConstructionException e) {
            throw new StormDeploymentException("Error while converting to XML storm query plan. " +
                    "Execution plan: " + executionPlanName + " Tenant: " + tenantId + ". " + e.getMessage(), e);
        }

        TopologySubmitter topologySubmitter = new TopologySubmitter(executionPlanName, builder.createTopology(), tenantId, resubmitRetryInterval);
        synchronized (toDeployTopologies) {
            toDeployTopologies.put(getTopologyName(executionPlanName, tenantId), topologySubmitter);
        }

        Thread deploymentThread = topologyManagerThreadFactory.newThread(topologySubmitter);
        deploymentThread.start();

    }

    public void killTopology(String executionPlanName, int tenantId) throws StormDeploymentException {
        try {
            synchronized (toDeployTopologies) {
                toDeployTopologies.remove(getTopologyName(executionPlanName, tenantId));
            }
            log.info("Killing storm topology '" + executionPlanName + "' of tenant '" + tenantId + "'");
            Nimbus.Client client = NimbusClient.getConfiguredClient(stormConfig).getClient();
            client.killTopologyWithOpts(getTopologyName(executionPlanName, tenantId), new KillOptions()); //provide topology name
        } catch (NotAliveException e) {
            // do nothing
        } catch (TException e) {
            throw new StormDeploymentException("Error connecting to Storm", e);
        }
    }

    private String getStringQueryPlan(Document document) throws TransformerException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter sw = new StringWriter();
        StreamResult result = new StreamResult(sw);
        DOMSource source = new DOMSource(document);
        transformer.transform(source, result);
        String xmlString = sw.toString();
        return xmlString;
    }

    public String getTopologyName(String executionPlanName, int tenantId) {
        return (executionPlanName + "[" + tenantId + "]");
    }

    class TopologySubmitter implements Runnable {
        private final String topologyName;
        StormTopology topology;
        int retryInterval;

        public TopologySubmitter(String executionPlanName, StormTopology topology,
                                 int tenantId, int resubmitRetryInterval) {
            this.topologyName = getTopologyName(executionPlanName, tenantId);
            this.topology = topology;
            this.retryInterval = resubmitRetryInterval;
        }

        @Override
        public void run() {
            String jobPrefix = "TopologySubmitterJob:" + Thread.currentThread().getId() + ", ";
            log.info(jobPrefix + "Job started to submit storm topology '" + topologyName + "'.");
            while (true) {
                if (!isToBeDeployed()) {
                    log.info(jobPrefix + "Aborting Storm deployment of '" + topologyName + "', as current job is outdated.");
                    return;
                }
                try {
                    if (isTopologyExist()) {
                        log.info(jobPrefix + "Killing already existing storm topology '" + topologyName + "' to re-submit");
                        KillOptions options = new KillOptions();
                        options.set_wait_secs(10);
                        try {
                            Nimbus.Client client = NimbusClient.getConfiguredClient(stormConfig).getClient();
                            client.killTopologyWithOpts(topologyName, options);
                            waitForTopologyToBeRemoved(jobPrefix);
                        } catch (TException e) {
                            log.error(jobPrefix + "Error connecting to storm when trying to kill topology '" + topologyName + "'", e);
                            log.info(jobPrefix + "Retrying to kill topology '" + topologyName + "' in " + retryInterval + " ms");
                            try {
                                Thread.sleep(retryInterval);
                            } catch (InterruptedException e1) {
                                //ignore
                            }
                        } catch (NotAliveException e) {
                            log.info(jobPrefix + "Topology '" + topologyName + "' is not alive to kill");
                        }
                    } else {
                        try {
                            String jsonConf = JSONValue.toJSONString(stormConfig);
                            synchronized (toDeployTopologies) {
                                if (isToBeDeployed()) {
                                    String uploadedJarLocation = StormSubmitter.submitJar(stormConfig, jarLocation);
                                    Nimbus.Client client = NimbusClient.getConfiguredClient(stormConfig).getClient();
                                    client.submitTopology(topologyName, uploadedJarLocation, jsonConf, topology);
                                    toDeployTopologies.remove(topologyName);
                                    log.info(jobPrefix + "Successfully submitted storm topology '" + topologyName + "'");
                                    return;
                                } else {
                                    log.info(jobPrefix + "Aborting Storm deployment of '" + topologyName + "', as current job is outdated.");
                                    return;
                                }
                            }
                        } catch (TException e) {
                            log.error(jobPrefix + "Error connecting to storm when trying to submit topology '" + topologyName + "'", e);
                            log.info(jobPrefix + "Retrying to submit topology '" + topologyName + "' in " + retryInterval + " ms");
                            try {
                                Thread.sleep(retryInterval);
                            } catch (InterruptedException e1) {
                                //ignore
                            }
                        } catch (InvalidTopologyException e) {
                            log.error(jobPrefix + "Cannot deploy, Invalid Strom topology '" + topologyName + "' found.", e);
                        } catch (AlreadyAliveException e) {
                            log.warn(jobPrefix + "Topology '" + topologyName + "' already existing. Trying to kill and re-submit", e);
                        }
                    }
                } catch (ServerUnavailableException e) {
                    log.error(jobPrefix + e.getMessage(), e);
                    log.info(jobPrefix + "Retrying to submit topology '" + topologyName + "' in " + retryInterval + " ms");
                    try {
                        Thread.sleep(retryInterval);
                    } catch (InterruptedException e1) {
                        //ignore
                    }
                }
            }
        }

        private boolean isToBeDeployed() {
            synchronized (toDeployTopologies) {
                TopologySubmitter existingTopologySubmitter = toDeployTopologies.get(topologyName);
                return existingTopologySubmitter != null && existingTopologySubmitter.equals(this);
            }
        }

        private boolean isTopologyExist() throws ServerUnavailableException {
            try {
                Nimbus.Client client = NimbusClient.getConfiguredClient(stormConfig).getClient();
                List<TopologySummary> topologies = client.getClusterInfo().get_topologies();
                for (TopologySummary topologySummary : topologies) {
                    if (topologySummary.get_name().equals(topologyName)) {
                        return true;
                    }
                }
                return false;
            } catch (TException e) {
                throw new ServerUnavailableException("Error connecting to storm when trying to check whether topology '" + topologyName + "' exist", e);
            } catch (RuntimeException e) {
                throw new ServerUnavailableException("Runtime Exception connecting to storm when trying to check whether topology '" + topologyName + "' exist", e);
            }
        }

        private void waitForTopologyToBeRemoved(String jobPrefix) throws TException, ServerUnavailableException {
            log.info(jobPrefix + "Waiting topology '" + topologyName + "' to be removed from Storm cluster");
            try {
                while (true) {
                    if (isTopologyExist()) {
                        Thread.sleep(5000);
                    } else {
                        Thread.sleep(2000);
                        log.info(jobPrefix + "Topology '" + topologyName + "' removed from Storm cluster");
                        return;
                    }
                }
            } catch (InterruptedException e) {
            }
        }
    }

    class TopologyManagerThreadFactory implements ThreadFactory {
        final AtomicInteger poolNumber = new AtomicInteger(1);
        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String namePrefix;

        public TopologyManagerThreadFactory(String threadPoolExecutorName) {
            SecurityManager s = System.getSecurityManager();
            this.group = s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            this.namePrefix = "TopologyManager-" + threadPoolExecutorName + "-pool-" + poolNumber.getAndIncrement() + "-thread-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(this.group, r, this.namePrefix + this.threadNumber.getAndIncrement(), 0L);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }

            if (t.getPriority() != 5) {
                t.setPriority(5);
            }

            return t;
        }
    }

}