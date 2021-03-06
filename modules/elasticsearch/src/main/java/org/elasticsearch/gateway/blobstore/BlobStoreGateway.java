/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gateway.blobstore;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.blobstore.*;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.builder.BinaryXContentBuilder;
import org.elasticsearch.gateway.Gateway;
import org.elasticsearch.gateway.GatewayException;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * @author kimchy (shay.banon)
 */
public abstract class BlobStoreGateway extends AbstractLifecycleComponent<Gateway> implements Gateway {

    private BlobStore blobStore;

    private ByteSizeValue chunkSize;

    private BlobPath basePath;

    private ImmutableBlobContainer metaDataBlobContainer;

    private volatile int currentIndex;

    protected BlobStoreGateway(Settings settings) throws IOException {
        super(settings);
    }

    protected void initialize(BlobStore blobStore, ClusterName clusterName) throws IOException {
        this.blobStore = blobStore;
        this.chunkSize = componentSettings.getAsBytesSize("chunk_size", null);
        this.basePath = BlobPath.cleanPath().add(clusterName.value());
        this.metaDataBlobContainer = blobStore.immutableBlobContainer(basePath.add("metadata"));
        this.currentIndex = findLatestIndex();
        logger.debug("Latest metadata found at index [" + currentIndex + "]");
    }

    @Override public String toString() {
        return type() + "://" + blobStore + "/" + basePath;
    }

    public BlobStore blobStore() {
        return blobStore;
    }

    public BlobPath basePath() {
        return basePath;
    }

    public ByteSizeValue chunkSize() {
        return this.chunkSize;
    }

    @Override public void reset() throws Exception {
        blobStore.delete(BlobPath.cleanPath());
    }

    @Override protected void doStart() throws ElasticSearchException {
    }

    @Override protected void doStop() throws ElasticSearchException {
    }

    @Override protected void doClose() throws ElasticSearchException {
    }

    @Override public MetaData read() throws GatewayException {
        try {
            this.currentIndex = findLatestIndex();
        } catch (IOException e) {
            throw new GatewayException("Failed to find latest metadata to read from", e);
        }
        if (currentIndex == -1)
            return null;
        String metaData = "metadata-" + currentIndex;

        try {
            return readMetaData(metaDataBlobContainer.readBlobFully(metaData));
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("Failed to read metadata [" + metaData + "] from gateway", e);
        }
    }

    @Override public void write(MetaData metaData) throws GatewayException {
        final String newMetaData = "metadata-" + (currentIndex + 1);
        BinaryXContentBuilder builder;
        try {
            builder = XContentFactory.contentBinaryBuilder(XContentType.JSON);
            builder.prettyPrint();
            builder.startObject();
            MetaData.Builder.toXContent(metaData, builder, ToXContent.EMPTY_PARAMS);
            builder.endObject();
        } catch (IOException e) {
            throw new GatewayException("Failed to serialize metadata into gateway", e);
        }

        try {
            metaDataBlobContainer.writeBlob(newMetaData, new ByteArrayInputStream(builder.unsafeBytes(), 0, builder.unsafeBytesLength()), builder.unsafeBytesLength());
        } catch (IOException e) {
            throw new GatewayException("Failed to write metadata [" + newMetaData + "]", e);
        }

        currentIndex++;

        try {
            metaDataBlobContainer.deleteBlobsByFilter(new BlobContainer.BlobNameFilter() {
                @Override public boolean accept(String blobName) {
                    return blobName.startsWith("metadata-") && !newMetaData.equals(blobName);
                }
            });
        } catch (IOException e) {
            logger.debug("Failed to delete old metadata, will do it next time", e);
        }
    }

    private int findLatestIndex() throws IOException {
        ImmutableMap<String, BlobMetaData> blobs = metaDataBlobContainer.listBlobsByPrefix("metadata-");

        int index = -1;
        for (BlobMetaData md : blobs.values()) {
            if (logger.isTraceEnabled()) {
                logger.trace("[findLatestMetadata]: Processing [" + md.name() + "]");
            }
            String name = md.name();
            int fileIndex = Integer.parseInt(name.substring(name.indexOf('-') + 1));
            if (fileIndex >= index) {
                // try and read the meta data
                try {
                    readMetaData(metaDataBlobContainer.readBlobFully(name));
                    index = fileIndex;
                } catch (IOException e) {
                    logger.warn("[findLatestMetadata]: Failed to read metadata from [" + name + "], ignoring...", e);
                }
            }
        }

        return index;
    }

    private MetaData readMetaData(byte[] data) throws IOException {
        XContentParser parser = null;
        try {
            parser = XContentFactory.xContent(XContentType.JSON).createParser(data);
            return MetaData.Builder.fromXContent(parser, settings);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }
}
