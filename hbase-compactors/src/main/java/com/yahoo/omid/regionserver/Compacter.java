package com.yahoo.omid.regionserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.Store;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yahoo.omid.client.ColumnWrapper;
import com.yahoo.omid.tso.messages.MinimumTimestamp;


/**
 * Garbage collector for stale data: triggered upon HBase compactions,
 * it removes data from uncommitted transactions older than the
 *
 */
public class Compacter extends BaseRegionObserver {
    private static final Logger LOG = LoggerFactory.getLogger(Compacter.class);
    


    private static ExecutorService bossExecutor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
            .setNameFormat("compacter-boss-%d").build());
    private static ExecutorService workerExecutor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
            .setNameFormat("compacter-worker-%d").build());
    private String tsoHost;
    private int tsoPort;
    private volatile long minTimestamp;
    private ClientBootstrap bootstrap;
    private ChannelFactory factory;
    private Channel channel;

    public Compacter() {
        LOG.debug("Compacter initialized via empty constructor");
    }

    public Compacter(String tsoHost, int tsoPort) {
        this.tsoHost = tsoHost;
        this.tsoPort = tsoPort;
        LOG.debug(String.format("Compacter initialized, TSO %s:%s", tsoHost, tsoPort));
    }

    @Override
    public void start(CoprocessorEnvironment e) throws IOException {
        LOG.info("Starting compacter");
        Configuration conf = e.getConfiguration();
        factory = new NioClientSocketChannelFactory(bossExecutor, workerExecutor, 3);
        bootstrap = new ClientBootstrap(factory);

        bootstrap.getPipeline().addLast("decoder", new ObjectDecoder());
        bootstrap.getPipeline().addLast("handler", new Handler());
        bootstrap.setOption("tcpNoDelay", false);
        bootstrap.setOption("keepAlive", true);
        bootstrap.setOption("reuseAddress", true);
        bootstrap.setOption("connectTimeoutMillis", 100);

        String host = conf.get("tso.host", tsoHost);
        int port = conf.getInt("tso.port", tsoPort) + 1;
        LOG.debug(String.format("TSO %s:%s", tsoHost, tsoPort));

        if (host == null) {
            throw new IOException("tso.host missing from configuration");
        }

        bootstrap.connect(new InetSocketAddress(host, port)).addListener(new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    LOG.info("Compacter connected!");
                    channel = future.getChannel();
                } else {
                    LOG.error("Connection failed");
                }
            }
        });
    }

    @Override
    public void stop(CoprocessorEnvironment e) throws IOException {
        if (channel != null) {
            LOG.debug("Calling close");
            channel.close();
        }
        LOG.debug("Compacter stopped");
    }

    @Override
    public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> e, Store store,
            InternalScanner scanner) {
        if (e.getEnvironment().getRegion().getRegionInfo().isMetaTable()) {
            return scanner;
        } else {
            return new CompacterScanner(scanner, minTimestamp);
        }
    }

    private static class CompacterScanner implements InternalScanner {
        private InternalScanner internalScanner;
        private long minTimestamp;
        private Set<ColumnWrapper> columnsSeen = new HashSet<ColumnWrapper>();
        private byte[] lastRowId = null;

        public CompacterScanner(InternalScanner internalScanner, long minTimestamp) {
            this.minTimestamp = minTimestamp;
            this.internalScanner = internalScanner;
            LOG.debug("Created scanner for cleaning up uncommitted operations older than [{}]", minTimestamp);
        }

        @Override
        public boolean next(List<KeyValue> results) throws IOException {
            return next(results, -1);
        }

        @Override
        public boolean next(List<KeyValue> result, int limit) throws IOException {
            boolean moreRows = false;
            List<KeyValue> raw = new ArrayList<KeyValue>(limit);
            while (limit == -1 || result.size() < limit) {
                int toReceive = limit == -1 ? -1 : limit - result.size();
                moreRows = internalScanner.next(raw, toReceive);
                if (raw.size() > 0) {
                    byte[] currentRowId = raw.get(0).getRow();
                    if (!Arrays.equals(currentRowId, lastRowId)) {
                        columnsSeen.clear();
                        lastRowId = currentRowId;
                    }
                }
                for (KeyValue kv : raw) {
                    ColumnWrapper column = new ColumnWrapper(kv.getFamily(), kv.getQualifier());
                    if (kv.getTimestamp() > minTimestamp || columnsSeen.add(column)) {
                        result.add(kv);
                    } else if (LOG.isTraceEnabled()){
                        LOG.trace("Discarded " + kv);
                    }
                }
                if (raw.size() < toReceive || toReceive == -1) {
                    columnsSeen.clear();
                    break;
                }
                raw.clear();
            }
            if (!moreRows) {
                columnsSeen.clear();
            }
            return moreRows;
        }

        @Override
        public void close() throws IOException {
            internalScanner.close();
        }

        @Override
        public boolean next(List<KeyValue> results, String metric) throws IOException {
            return next(results);
        }

        @Override
        public boolean next(List<KeyValue> result, int limit, String metric) throws IOException {
            return next(result, limit);
        }

    }

    private class Handler extends SimpleChannelUpstreamHandler {
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            Object message = e.getMessage();
            if (message instanceof MinimumTimestamp) {
                Compacter.this.minTimestamp = ((MinimumTimestamp) message).getTimestamp();
            } else {
                LOG.error("Unexpected message: " + message);
            }
        }
    }
}
