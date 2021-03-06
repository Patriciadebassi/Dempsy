/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nokia.dempsy;

import static com.nokia.dempsy.TestUtils.poll;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.Assert;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.nokia.dempsy.Dempsy.Application.Cluster.Node;
import com.nokia.dempsy.TestUtils.Condition;
import com.nokia.dempsy.TestUtils.JunkDestination;
import com.nokia.dempsy.annotations.Activation;
import com.nokia.dempsy.annotations.MessageHandler;
import com.nokia.dempsy.annotations.MessageKey;
import com.nokia.dempsy.annotations.MessageProcessor;
import com.nokia.dempsy.annotations.Output;
import com.nokia.dempsy.annotations.Start;
import com.nokia.dempsy.cluster.ClusterInfoSession;
import com.nokia.dempsy.cluster.ClusterInfoSessionFactory;
import com.nokia.dempsy.cluster.DisruptibleSession;
import com.nokia.dempsy.cluster.invm.LocalClusterSessionFactory;
import com.nokia.dempsy.cluster.zookeeper.ZookeeperTestServer.InitZookeeperServerBean;
import com.nokia.dempsy.config.ClusterId;
import com.nokia.dempsy.container.MpContainer;
import com.nokia.dempsy.executor.DefaultDempsyExecutor;
import com.nokia.dempsy.messagetransport.tcp.TcpReceiver;
import com.nokia.dempsy.messagetransport.tcp.TcpReceiverAccess;
import com.nokia.dempsy.monitoring.coda.MetricGetters;
import com.nokia.dempsy.router.DecentralizedRoutingStrategy.DefaultRouterSlotInfo;
import com.nokia.dempsy.serialization.kryo.KryoOptimizer;

public class TestDempsy
{
   /**
    * Setting 'hardcore' to true causes EVERY SINGLE IMPLEMENTATION COMBINATION to be used in 
    * every runAllCombinations call. This can make TestDempsy run for a loooooong time.
    */
   public static boolean hardcore = false;
   
   private static Logger logger = LoggerFactory.getLogger(TestDempsy.class);
   private static long baseTimeoutMillis = 20000; // 20 seconds
   
   String[] dempsyConfigs = new String[] { "testDempsy/Dempsy.xml" };
   
   String[] clusterManagers = new String[]{ "testDempsy/ClusterInfo-ZookeeperActx.xml", "testDempsy/ClusterInfo-LocalActx.xml" };
   String[][] transports = new String[][] {
         { "testDempsy/Transport-PassthroughActx.xml", "testDempsy/Transport-PassthroughBlockingActx.xml" }, 
         { "testDempsy/Transport-BlockingQueueActx.xml" }, 
         { "testDempsy/Transport-TcpNoBatchingActx.xml", "testDempsy/Transport-TcpFailSlowActx.xml", "testDempsy/Transport-TcpWithOverflowActx.xml", "testDempsy/Transport-TcpBatchedOutputActx.xml" }
   };
   
   String[] serializers = new String[]
         { "testDempsy/Serializer-JavaActx.xml", "testDempsy/Serializer-KryoActx.xml", "testDempsy/Serializer-KryoOptimizedActx.xml" };
   
   // bad combinations.
   List<ClusterId> badCombos = Arrays.asList(new ClusterId[] {
         // this is a hack ... use a ClusterId as a String tuple for comparison
         
         // the passthrough Destination is not serializable but zookeeper requires it to be
         new ClusterId("testDempsy/ClusterInfo-ZookeeperActx.xml", "testDempsy/Transport-PassthroughActx.xml") , 
         new ClusterId("testDempsy/ClusterInfo-ZookeeperActx.xml", "testDempsy/Transport-PassthroughBlockingActx.xml") , 
         
         // the blockingqueue Destination is not serializable but zookeeper requires it to be
         new ClusterId("testDempsy/ClusterInfo-ZookeeperActx.xml", "testDempsy/Transport-BlockingQueueActx.xml") 
   });
 
   private static InitZookeeperServerBean zkServer = null;

   public static String onodes = null;
   public static String oapp = null;
   public static String ocluster = null;
   public static String oslots = null;
   
   private static final void resetProp(String key, String val)
   {
      if (val != null)
         System.setProperty(key, val);
      else
         System.clearProperty(key);
   }
   
   @BeforeClass
   public static void setupZookeeperSystemVars() throws IOException
   {
      onodes = System.getProperty("min_nodes_for_cluster");
      oslots = System.getProperty("total_slots_for_cluster");
      oapp = System.getProperty("application");
      ocluster = System.getProperty("cluster");
      
      System.setProperty("application", "test-app");
      System.setProperty("cluster", "test-cluster2");
      zkServer = new InitZookeeperServerBean();
   }
   
   @AfterClass
   public static void shutdownZookeeper()
   {
      resetProp("min_nodes_for_cluster",onodes);
      resetProp("total_slots_for_cluster", oslots);
      resetProp("application",oapp);
      resetProp("cluster",ocluster);
      
      zkServer.stop();
   }
   
   @Before
   public void init()
   {
      LocalClusterSessionFactory.completeReset();
      KeySourceImpl.disruptSession = false;
      KeySourceImpl.infinite = false;
      KeySourceImpl.pause = new CountDownLatch(0);
      KeySourceImpl.maxcount = 2;
      KeySourceImpl.lastCreated = null;

      TestMp.currentOutputCount = 10;
      TestMp.activateCheckedException = false;
      System.setProperty("min_nodes_for_cluster", "1");
      System.setProperty("total_slots_for_cluster", "20");
   }
   
   public static class TestMessage implements Serializable
   {
      private static final long serialVersionUID = 1L;
      private String val;
      
      @SuppressWarnings("unused") // required for Kryo
      private TestMessage() {} 
      
      public TestMessage(String val) { this.val = val; }
      
      @MessageKey
      public String get() { return val; } 
      
      public boolean equals(Object o) 
      {
         return o == null ? false :
            String.valueOf(val).equals(String.valueOf(((TestMessage)o).val)); 
      }
      
      @Override
      public String toString() { return "TestMessage:\"" + val + "\""; }
   }
   
   public static class TestKryoOptimizer implements KryoOptimizer
   {

      @Override
      public void preRegister(Kryo kryo)
      {
         kryo.setRegistrationRequired(true);
      }

      @Override
      public void postRegister(Kryo kryo)
      {
         @SuppressWarnings("unchecked")
         FieldSerializer<TestMessage> valSer = (FieldSerializer<TestMessage>)kryo.getSerializer(TestMessage.class);
         valSer.setFieldsCanBeNull(false);
      }
      
   }
   
   public static class ActivateCheckedException extends Exception
   {
      private static final long serialVersionUID = 1L;
      public ActivateCheckedException(String message) { super(message); }
   }
   
   @MessageProcessor
   public static class TestMp implements Cloneable
   {
      public static int currentOutputCount = 10;
      
      // need a mutable object reference
      public AtomicReference<TestMessage> lastReceived = new AtomicReference<TestMessage>();
      public AtomicLong outputCount = new AtomicLong(0);
      public CountDownLatch outputLatch = new CountDownLatch(currentOutputCount);
      public AtomicInteger startCalls = new AtomicInteger(0);
      public AtomicInteger cloneCalls = new AtomicInteger(0);
      public AtomicLong handleCalls = new AtomicLong(0);
      public AtomicReference<String> failActivation = new AtomicReference<String>();
      public AtomicBoolean haveWaitedOnce = new AtomicBoolean(false);
      public static boolean activateCheckedException = false;
      
      @Start
      public void start()
      {
         startCalls.incrementAndGet();
      }
      
      @MessageHandler
      public void handle(TestMessage message)
      {
         lastReceived.set(message);
         handleCalls.incrementAndGet();
      }
      
      @Activation
      public void setKey(String key) throws ActivateCheckedException
      {
         // we need to wait at least once because sometime pre-instantiation 
         // goes so fast the test fails becuase it fails to register on the statsCollector.
         if (!haveWaitedOnce.get())
         {
            try { Thread.sleep(3); } catch (Throwable th) {}
            haveWaitedOnce.set(true);
         }

         if (key.equals(failActivation.get()))
         {
            String message = "Failed Activation For " + key;
            if (activateCheckedException)
               throw new ActivateCheckedException(message);
            else
               throw new RuntimeException(message);
         }
      }
      
      @Override
      public TestMp clone() throws CloneNotSupportedException 
      {
         cloneCalls.incrementAndGet();
         return (TestMp) super.clone();
      }
      
      @Output
      public void output()
      {
         outputCount.incrementAndGet();
         outputLatch.countDown();
      }
   }
   
   public static class OverflowHandler implements com.nokia.dempsy.messagetransport.OverflowHandler
   {

      @Override
      public void overflow(byte[] messageBytes)
      {
         logger.debug("Overflow:" + messageBytes);
      }
      
   }
   
   public static class TestAdaptor implements Adaptor
   {
      Dispatcher dispatcher;
      public Object lastSent;
      public volatile static boolean throwExceptionOnSetDispatcher = false; 
      
      @Override
      public void setDispatcher(Dispatcher dispatcher)
      {
         this.dispatcher = dispatcher;
         if (throwExceptionOnSetDispatcher) throw new RuntimeException("Forced RuntimeException"); 
      }
      
      @Override
      public void start() { }
      
      @Override
      public void stop() { }
      
      public void pushMessage(Object message)
      {
         lastSent = message;
         dispatcher.dispatch(message);
      }
   }
   
   public static class KeySourceImpl implements KeySource<String>
   {
      private Dempsy dempsy = null;
      private ClusterId clusterId = null;
      public static volatile boolean disruptSession = false;
      public static volatile boolean infinite = false;
      public static volatile CountDownLatch pause = new CountDownLatch(0);
      public static volatile KSIterable lastCreated = null;
      public static volatile int maxcount = 2;
      
      public void setDempsy(Dempsy dempsy) { this.dempsy = dempsy; }
      
      public void setClusterId(ClusterId clusterId) { this.clusterId = clusterId; }
      
      public class KSIterable implements Iterable<String>
      {
         public volatile String lastKey = "";
         public CountDownLatch m_pause = pause;
         public volatile boolean m_infinite = infinite;

         {
            lastCreated = this;
         }

         @Override
         public Iterator<String> iterator()
         {
            return new Iterator<String>()
            {
               long count = 0;

               @Override
               public boolean hasNext() { if (count >= 1) kickClusterInfoMgr(); return m_infinite ? true : (count < maxcount);  }

               @Override
               public String next() { try { m_pause.await(); } catch (InterruptedException ie) {} count++; return (lastKey = "test" + count);}

               @Override
               public void remove() { throw new UnsupportedOperationException(); }

               private void kickClusterInfoMgr() 
               {
                  if (!disruptSession)
                     return;
                  disruptSession = false; // one disruptSession
                  Dempsy.Application.Cluster c = dempsy.getCluster(clusterId);
                  Object session = TestUtils.getSession(c);
                  if (session instanceof DisruptibleSession)
                  {
                     DisruptibleSession dses = (DisruptibleSession)session;
                     dses.disrupt();
                  }
               }
            };
         }
         
      }
      
      @Override
      public Iterable<String> getAllPossibleKeys()
      {
         // The array is proxied to create the ability to rip out the cluster manager
         // in the middle of iterating over the key source. This is to create the 
         // condition in which the key source is being iterated while the routing strategy
         // is attempting to get slots.
         return new KSIterable();
      }
   }
   
   public abstract class Checker
   {
      public abstract void check(ApplicationContext context) throws Throwable;
      
      public void setup() { init(); }
   }
   
   private static class WaitForShutdown implements Runnable
   {

      public boolean shutdown = false;
      public Dempsy dempsy = null;
      public CountDownLatch waitForShutdownDoneLatch = new CountDownLatch(1);
      
      WaitForShutdown(Dempsy dempsy) { this.dempsy = dempsy; }
      
      @Override
      public void run()
      {
         try { dempsy.waitToBeStopped(); shutdown = true; } catch(InterruptedException e) {}
         waitForShutdownDoneLatch.countDown();
      }
   }
   
   static class AlternatingIterable implements Iterable<String>
   {
      boolean hardcore = false;
      List<String> strings = null;
      
      public AlternatingIterable(boolean hardcore, String[] strings)
      {
         this.hardcore = hardcore; 
         this.strings = Arrays.asList(strings);
      }
      
      @Override
      public Iterator<String> iterator()
      {
         return hardcore ? strings.iterator() : 
            new Iterator<String>()
         {
            boolean done = false;
            
            @Override
            public boolean hasNext() { return !done; }

            @Override
            public String next(){ done = true; return strings.get(runCount % strings.size()); }

            @Override
            public void remove() { throw new UnsupportedOperationException(); }
         };
      }
      
   }
   
   static int runCount = 0;
   
   public void runAllCombinations(String applicationContext, Checker checker) throws Throwable
   {
      for (String clusterManager : clusterManagers)
      {
         for (String[] alternatingTransports : transports)
         {
            // select one of the alternatingTransports
            for (String transport : new AlternatingIterable(hardcore,alternatingTransports))
            {
               for (String serializer : new AlternatingIterable(hardcore,serializers))
               {
                  // alternate the dempsy configs
                  for (String dempsyConfig : new AlternatingIterable(hardcore,dempsyConfigs))
                  {

                     if (! badCombos.contains(new ClusterId(clusterManager,transport)))
                     {
                        String pass = applicationContext + " test: " + (checker == null ? "none" : checker) + " using " + dempsyConfig + "," + clusterManager + "," + serializer + "," + transport;
                        try
                        {
                           logger.debug("*****************************************************************");
                           logger.debug(pass);
                           logger.debug("*****************************************************************");
                           
                           if (checker != null)
                           {
                              init(); // reset everything
                              checker.setup(); // allow modification to defaults for this test.
                           }

                           String[] ctx = { dempsyConfig, clusterManager, transport, serializer, "testDempsy/" + applicationContext };

                           logger.debug("Starting up the appliction context ...");
                           ClassPathXmlApplicationContext actx = new ClassPathXmlApplicationContext(ctx);
                           actx.registerShutdownHook();

                           Dempsy dempsy = (Dempsy)actx.getBean("dempsy");

                           assertTrue(pass,TestUtils.waitForClustersToBeInitialized(baseTimeoutMillis, dempsy));

                           WaitForShutdown waitingForShutdown = new WaitForShutdown(dempsy);
                           Thread waitingForShutdownThread = new Thread(waitingForShutdown,"Waiting For Shutdown");
                           waitingForShutdownThread.start();
                           Thread.yield();
                           
                           logger.debug("Running test ...");
                           if (checker != null)
                              checker.check(actx);
                           logger.debug("Done with test, stopping the application context ...");

                           actx.stop();
                           actx.destroy();

                           assertTrue(waitingForShutdown.waitForShutdownDoneLatch.await(baseTimeoutMillis, TimeUnit.MILLISECONDS));
                           assertTrue(waitingForShutdown.shutdown);

                           logger.debug("Finished this pass.");
                        }
                        catch (AssertionError re)
                        {
                           logger.error("***************** FAILED ON: " + pass);
                           throw re;
                        }
                        finally
                        {
                           LocalClusterSessionFactory.completeReset();
                        }

                        runCount++;
                     }
                  }
               }
            }
         }
      }
   }
   
   @Test 
   public void testIndividualClusterStart() throws Throwable
   {
      ClassPathXmlApplicationContext actx = new ClassPathXmlApplicationContext(
            "testDempsy/Dempsy-IndividualClusterStart.xml",
            "testDempsy/Transport-PassthroughActx.xml",
            "testDempsy/ClusterInfo-LocalActx.xml",
            "testDempsy/Serializer-KryoActx.xml",
            "testDempsy/SimpleMultistageApplicationActx.xml"
            );
      actx.registerShutdownHook();
      
      Dempsy dempsy = (Dempsy)actx.getBean("dempsy");
      assertNotNull(dempsy);
      
      Dempsy.Application.Cluster cluster = dempsy.getCluster(new ClusterId("test-app", "test-cluster0"));
      assertNull(cluster);

      cluster = dempsy.getCluster(new ClusterId("test-app", "test-cluster1"));
      assertNull(cluster);

      cluster = dempsy.getCluster(new ClusterId("test-app", "test-cluster2"));
      assertNotNull(cluster);
      assertEquals(1,cluster.getNodes().size());

      cluster = dempsy.getCluster(new ClusterId("test-app", "test-cluster3"));
      assertNull(cluster);

      cluster = dempsy.getCluster(new ClusterId("test-app", "test-cluster4"));
      assertNull(cluster);

      actx.stop();
      actx.destroy();
   }

   @Test(expected=BeanCreationException.class) 
   public void testInValidClusterStart() throws Throwable
   {
      new ClassPathXmlApplicationContext(
            "testDempsy/Dempsy-InValidClusterStart.xml",
            "testDempsy/Transport-PassthroughActx.xml",
            "testDempsy/ClusterInfo-LocalActx.xml",
            "testDempsy/Serializer-KryoActx.xml",
            "testDempsy/SimpleMultistageApplicationActx.xml"
            );
   }
   
   @Test
   public void testTcpTransportExecutorConfigurationThroughApplication() throws Throwable
   {
      ClassPathXmlApplicationContext actx = null;
      DefaultDempsyExecutor executor = null;
      try
      {
         actx = new ClassPathXmlApplicationContext(
               "testDempsy/Dempsy-IndividualClusterStart.xml",
               "testDempsy/Transport-TcpNoBatchingActx.xml",
               "testDempsy/ClusterInfo-LocalActx.xml",
               "testDempsy/Serializer-KryoActx.xml",
               "testDempsy/SimpleMultistageApplicationWithExecutorActx.xml"
               );
         actx.registerShutdownHook();

         Dempsy dempsy = (Dempsy)actx.getBean("dempsy");
         for (Dempsy.Application.Cluster cluster : dempsy.applications.get(0).appClusters)
         {
            // get the receiver from the node
            TcpReceiver r = (TcpReceiver)cluster.getNodes().get(0).receiver;
            executor = (DefaultDempsyExecutor)TcpReceiverAccess.getExecutor(r);
            assertEquals(123456,executor.getMaxNumberOfQueuedLimitedTasks());
            assertTrue(executor.isRunning());
         }
      }
      finally
      {
         try { actx.stop(); } catch (Throwable th) {}
         try { actx.destroy(); } catch(Throwable th) {}
      }
      
      assertNotNull(executor);
      assertTrue(!executor.isRunning());
   }

   @Test
   public void testAdaptorThrowsRuntimeOnSetDispatcher() throws Throwable
   {
      TestAdaptor.throwExceptionOnSetDispatcher = true;
      ClassPathXmlApplicationContext actx = null;
      boolean gotException = false;
      
      try
      {
         actx = new ClassPathXmlApplicationContext(
               "testDempsy/Dempsy.xml",
               "testDempsy/Transport-PassthroughActx.xml",
               "testDempsy/ClusterInfo-LocalActx.xml",
               "testDempsy/Serializer-KryoActx.xml",
               "testDempsy/SimpleMultistageApplicationActx.xml"
               );
         actx.registerShutdownHook();
      }
      catch (Throwable th)
      {
         assertEquals("Forced RuntimeException",th.getCause().getLocalizedMessage());
         gotException = true;
      }
      finally
      {
         TestAdaptor.throwExceptionOnSetDispatcher = false;
         if (actx != null)
         {
            actx.stop();
            actx.destroy();
         }
         
      }
      
      assertTrue(gotException);
   }
   

   @Test
   public void testStartupShutdown() throws Throwable
   {
      runAllCombinations("SimpleMultistageApplicationActx.xml", new Checker()
      {
         @Override
         public void check(ApplicationContext context) throws Throwable { }
         
         public String toString() { return "testStartupShutdown"; }

      });

   }
   
   @Test
   public void testForkedFailure() throws Throwable
   {
      runAllCombinations("SimpleMultistageApplicationActx.xml", new Checker()
      {
         @Override
         public void check(ApplicationContext context) throws Throwable
         {
            final AtomicBoolean stopIt = new AtomicBoolean(false);
            final AtomicBoolean failed = new AtomicBoolean(false);
            final AtomicBoolean stopped = new AtomicBoolean(false);
            
            try
            {
               // start things and verify that the init method was called
               Dempsy dempsy = (Dempsy)context.getBean("dempsy");

               final TestAdaptor adaptor = (TestAdaptor) getAdaptor(dempsy, "test-app","test-cluster0");
               assertNotNull(adaptor);

               Thread adaptorThread = new Thread(new Runnable()
               {
                  @Override
                  public void run()
                  {
                     try
                     {
                        long i = 0;
                        while (!stopIt.get())
                        {
                           adaptor.pushMessage(new TestMessage("" + i));
                           i++;
                           Thread.sleep(10);
                        }
                     }
                     catch (Throwable th)
                     {
                        failed.set(true);
                     }
                     finally
                     {
                        stopped.set(true);
                     }
                  }
               }, "testForkedFailure-Adaptor Thread ");
               adaptorThread.start();
               
               TestMp[] mps = new TestMp[3];
               DisruptibleSession[] sessions = new DisruptibleSession[3];
               

               for (int i = 0; i < mps.length; i++)
               {
                  String cluster = "test-cluster" + (i + 1);
                  mps[i] = (TestMp) getMp(dempsy,"test-app",cluster);
                  sessions[i] = (DisruptibleSession)(dempsy.getCluster(new ClusterId("test-app", cluster)).getNodes().get(0).retouRteg().getClusterSession());
                  assertEquals(1, mps[i].startCalls.get());
               }

               for (int i = 0; i < mps.length; i++)
               {
                  for (int j = 0; j < mps.length; j++)
                  {
                     if (i != j)
                        assertTrue(mps[i] != mps[j]);
                  }
               }
               
               // now check to see that data is going to all 3.
               for (int i = 0; i < mps.length; i++)
               {
                  assertTrue(poll(baseTimeoutMillis, mps[i], new Condition<TestMp>() { public boolean conditionMet(TestMp o)
                  {
                     return o.handleCalls.get() > 0;
                  }}));
               }

               int curPos = 0;
               for (int j = 0; j < 3; j++)
               {
                  // now kill a cluster or 2 (or 3)
                  for (int k = 0; k <= j; k++)
                     sessions[curPos++ % sessions.length].disrupt();

                  for (int i = 0; i < mps.length; i++)
                  {
                     final long curCalls = mps[i].handleCalls.get();
                     assertTrue(poll(baseTimeoutMillis, mps[i], new Condition<TestMp>() { public boolean conditionMet(TestMp o)
                     {
                        return o.handleCalls.get() > curCalls;
                     }}));
                  }
               }               
            }
            finally
            {
               stopIt.set(true);
               assertFalse(failed.get());
               
               assertTrue(poll(baseTimeoutMillis, stopped, new Condition<AtomicBoolean>() { public boolean conditionMet(AtomicBoolean o) { return o.get(); }}));
            }
         }
         
         public String toString() { return "testForkedFailure"; }

      });

   }
   
   @Test
   public void testMpStartMethod() throws Throwable
   {
      runAllCombinations("SinglestageApplicationActx.xml",
          new Checker()   
            {
               @Override
               public void check(ApplicationContext context) throws Throwable
               {
                  TestAdaptor adaptor = (TestAdaptor)context.getBean("adaptor");
                  Object message = new Object();

                  // start things and verify that the init method was called
                  Dempsy dempsy = (Dempsy)context.getBean("dempsy");
                  TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");
                  assertEquals(1, mp.startCalls.get());
                      
                  // now send a message through
                      
                  message = new TestMessage("HereIAm - testMPStartMethod");
                  adaptor.pushMessage(message);
                      
                  // instead of the latch we are going to poll for the correct result
                  // wait for it to be received.
                  final Object msg = message;
                  assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return msg.equals(mp.lastReceived.get()); } }));
                      
                  // verify we haven't called it again, not that there's really
                  // a way to given the code
                  assertEquals(1, mp.startCalls.get());
               }
               
               public String toString() { return "testMPStartMethod"; }
            });
   }
   
   @Test
   public void testMessageThrough() throws Throwable
   {
      runAllCombinations("SinglestageApplicationActx.xml",
            new Checker()
            {
               @Override
               public void check(ApplicationContext context) throws Throwable
               {
                  TestAdaptor adaptor = (TestAdaptor)context.getBean("adaptor");
                  Object message = new Object();
                  adaptor.pushMessage(message);
                  
                  // check that the message didn't go through.
                  Dempsy dempsy = (Dempsy)context.getBean("dempsy");
                  TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");
                  assertTrue(mp.lastReceived.get() == null);
                  
                  TestAdaptor adaptor2 = (TestAdaptor)getAdaptor(dempsy, "test-app","test-cluster0");
                  assertEquals(adaptor,adaptor2);
                  
                  assertEquals(adaptor.lastSent, message);
                  
                  // now send a message through
                  
                  message = new TestMessage("HereIAm - testMessageThrough");
                  adaptor.pushMessage(message);
                  
                  // instead of the latch we are going to poll for the correct result
                  // wait for it to be received.
                  final Object msg = message;
                  assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return msg.equals(mp.lastReceived.get()); } }));
                  
                  assertEquals(adaptor2.lastSent,message);
                  assertEquals(adaptor2.lastSent,mp.lastReceived.get());
                  
               }
               
               public String toString() { return "testMessageThrough"; }
            });
   }
   
   @Test
   public void testMessageThroughWithClusterFailure() throws Throwable
   {
      runAllCombinations("SinglestageApplicationActx.xml",
            new Checker()
            {
               @Override
               public void check(ApplicationContext context) throws Throwable
               {
                  // check that the message didn't go through.
                  Dempsy dempsy = (Dempsy)context.getBean("dempsy");
                  TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");

                  final AtomicReference<TestMessage> msg = new AtomicReference<TestMessage>();
                  final TestAdaptor adaptor = (TestAdaptor)context.getBean("adaptor");
                  // now send a message through
                  
                  TestMessage message = new TestMessage("HereIAm - testMessageThrough");
                  adaptor.pushMessage(message);
                  
                  // instead of the latch we are going to poll for the correct result
                  // wait for it to be received.
                  msg.set(message);
                  assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return msg.get().equals(mp.lastReceived.get()); } }));
                  
                  assertEquals(adaptor.lastSent,message);
                  assertEquals(adaptor.lastSent,mp.lastReceived.get());
                  
                  // now go into a disruption loop
                  ClusterInfoSession session = TestUtils.getSession(dempsy.getCluster(new ClusterId("test-app","test-cluster1")));
                  assertNotNull(session);
                  DisruptibleSession dsess = (DisruptibleSession) session;
                  
                  final AtomicBoolean stopSending = new AtomicBoolean(false);
                  
                  Thread thread = new Thread(new Runnable()
                  {
                     
                     @Override
                     public void run()
                     {
                        long count = 0;
                        while (!stopSending.get())
                        {
                           adaptor.pushMessage(new TestMessage("Hello:" + count++));
                           try { Thread.sleep(1); } catch (Throwable th) {}
                        }
                     }
                  });
                  
                  thread.setDaemon(true);
                  thread.start();
                  
                  for (int i = 0; i < 10; i++)
                  {
                     logger.trace("=========================");
                     dsess.disrupt();
                     
                     // now wait until more messages come through
                     final long curCount = mp.handleCalls.get();
                     assertTrue(poll(baseTimeoutMillis,mp, new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.handleCalls.get() > curCount; } }));
                  }
                  
                  stopSending.set(true);
               }
               
               public String toString() { return "testMessageThroughWithClusterFailure"; }
            });
   }

   
   @Test
   public void testOutPutMessage() throws Throwable
   {
      runAllCombinations("SinglestageOutputApplicationActx.xml",
            new Checker()
            {
               @Override
               public void check(ApplicationContext context) throws Throwable
               {
                  TestAdaptor adaptor = (TestAdaptor)context.getBean("adaptor");
                  TestMessage message = new TestMessage("output");
                  adaptor.pushMessage(message); // this causes the container to clone the Mp
                  
                  // Now wait for the output call to be made 10 times (or so).
                  Dempsy dempsy = (Dempsy)context.getBean("dempsy");
                  TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");
                  assertTrue(mp.outputLatch.await(baseTimeoutMillis, TimeUnit.MILLISECONDS));
                  assertTrue(mp.outputCount.get()>=10);
               }
               
               public String toString() { return "testOutPutMessage"; }

            });
   }


   @Test
   public void testCronOutPutMessage() throws Throwable
   {
      // since the cron output message can only go to 1 second resolution,
      //  we need to drop the number of attempt to 3. Otherwise this test
      //  takes way too long.
      runAllCombinations("SinglestageOutputApplicationActx.xml",
            new Checker()
            {
               @Override
               public void check(ApplicationContext context) throws Throwable
               {
                  TestAdaptor adaptor = (TestAdaptor)context.getBean("adaptor");
                  TestMessage message = new TestMessage("output");
                  adaptor.pushMessage(message); // this causes the container to clone the Mp
                  
                  // Now wait for the output call to be made 10 times (or so).
                  Dempsy dempsy = (Dempsy)context.getBean("dempsy");
                  TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster2");
                  assertTrue(mp.outputLatch.await(baseTimeoutMillis, TimeUnit.MILLISECONDS));
                  assertTrue(mp.outputCount.get()>=3);
               }
               
               public String toString() { return "testCronOutPutMessage"; }
               
               @Override
               public void setup() { TestMp.currentOutputCount = 3; }

            });
   }

   @Test
   public void testExplicitDesintationsStartup() throws Throwable
   {
      runAllCombinations("MultistageApplicationExplicitDestinationsActx.xml",
            new Checker()
            {
               @Override
               public void check(ApplicationContext context) throws Throwable { }
               
               public String toString() { return "testExplicitDesintationsStartup"; }

            });
   }
   
   private static Object getMp(Dempsy dempsy, String appName, String clusterName)
   {
      Dempsy.Application.Cluster cluster = dempsy.getCluster(new ClusterId(appName,clusterName));
      Dempsy.Application.Cluster.Node node = cluster.getNodes().get(0); // currently there is one node per cluster.
      return node.clusterDefinition.getMessageProcessorPrototype();
   }
   
   private static Adaptor getAdaptor(Dempsy dempsy, String appName, String clusterName)
   {
      Dempsy.Application.Cluster cluster = dempsy.getCluster(new ClusterId(appName,clusterName));
      Dempsy.Application.Cluster.Node node = cluster.getNodes().get(0); // currently there is one node per cluster.
      return node.clusterDefinition.getAdaptor();
   }
   
   @Test
   public void testMpKeyStore() throws Throwable
   {
      runMpKeyStoreTest("testMpKeyStore",false);
   }
   
   @Test
   public void testMpKeyStoreWithFailingClusterManager() throws Throwable
   {
      runMpKeyStoreTest("testMpKeyStoreWithFailingClusterManager",true);
   }
   
   public void runMpKeyStoreTest(final String methodName, final boolean disruptSession) throws Throwable
   {
      Checker checker = new Checker()   
      {
         @Override
         public void check(ApplicationContext context) throws Throwable
         {
            // start things and verify that the init method was called
            Dempsy dempsy = (Dempsy)context.getBean("dempsy");
            TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");
                
            // verify we haven't called it again, not that there's really
            // a way to given the code
            assertEquals(1, mp.startCalls.get());

            // wait for clone calls to reach at least 2
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.cloneCalls.get()==2; } }));
            
            final TestAdaptor adaptor = (TestAdaptor)context.getBean("adaptor");
            
            // if the session has been disrupted then this may take some time to work again.
            // wait until it works again.
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) throws Throwable
            {
               adaptor.pushMessage(new TestMessage("output")); // this causes the container to clone the Mp
               Thread.sleep(100); // wait for it to be received.
               return mp.cloneCalls.get()==3; // this will not go past 3 as long as the same TestMessage is sent.
            } }));

            adaptor.pushMessage(new TestMessage("test1")); // this WONT causes the container to clone the Mp because test1 was already pre-instantiated.
            
            Thread.sleep(100); // give it a little time.

            // wait for it to be received.
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.cloneCalls.get()==3; } }));
            List<Node> nodes = dempsy.getCluster(new ClusterId("test-app","test-cluster1")).getNodes();
            Assert.assertNotNull(nodes);
            Assert.assertTrue(nodes.size()>0);
            Node node = nodes.get(0);
            Assert.assertNotNull(node);
            double duration = ((MetricGetters)node.getStatsCollector()).getPreInstantiationDuration();
            Assert.assertTrue(duration>0.0);
         }
         
         public String toString() { return methodName; }
         
         @Override
         public void setup() { KeySourceImpl.disruptSession = disruptSession; }
      };

      runAllCombinations("SinglestageWithKeyStoreApplicationActx.xml", checker);
      runAllCombinations("SinglestageWithKeyStoreAndExecutorApplicationActx.xml", checker);
   }
   
   @Test
   public void testOverlappingKeyStoreCalls() throws Throwable
   {
      Checker checker = new Checker()   
      {
         @Override
         public void check(ApplicationContext context) throws Throwable
         {
            // wait until the KeySourceImpl has been created
            assertTrue(poll(baseTimeoutMillis,null,new Condition<Object>() { @Override public boolean conditionMet(Object mp) {  return KeySourceImpl.lastCreated != null; } }));
            final KeySourceImpl.KSIterable firstCreated = KeySourceImpl.lastCreated;
            
            // start things and verify that the init method was called
            Dempsy dempsy = (Dempsy)context.getBean("dempsy");
            TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");

            Dempsy.Application.Cluster c = dempsy.getCluster(new ClusterId("test-app","test-cluster1"));
            assertNotNull(c);
            Dempsy.Application.Cluster.Node node = c.getNodes().get(0);
            assertNotNull(node);
            
            MpContainer container = node.getMpContainer();
            
            // let it go and wait until there's a few keys.
            firstCreated.m_pause.countDown();
            
            // as the KeySource iterates, this will increase
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.cloneCalls.get() > 10000; } }));

            // prepare the next countdown latch
            KeySourceImpl.pause = new CountDownLatch(0); // just let the 2nd one go
            
            // I want the next one to stop at 2
            KeySourceImpl.infinite = false;

            // Now force another call while the first is running
            container.keyspaceResponsibilityChanged(node.strategyInbound, false, true);
            
            // wait until the second one is created
            assertTrue(poll(baseTimeoutMillis,null,new Condition<Object>() { @Override public boolean conditionMet(Object mp) {  return KeySourceImpl.lastCreated != null && firstCreated != KeySourceImpl.lastCreated; } }));
            
            // now the first one should be done and therefore no longer incrementing.
            String lastKeyOfFirstCreated = firstCreated.lastKey;

            // and the second one should be done also and stopped at 2.
            final KeySourceImpl.KSIterable secondCreated = KeySourceImpl.lastCreated;
            assertTrue(firstCreated != secondCreated);
            
            assertTrue(poll(baseTimeoutMillis,null,new Condition<Object>() { @Override public boolean conditionMet(Object mp) {  return "test2".equals(secondCreated.lastKey); } }));
            
            Thread.sleep(50);
            assertEquals(lastKeyOfFirstCreated,firstCreated.lastKey); // make sure the first one isn't still moving on
            assertEquals("test2",secondCreated.lastKey);
            
            // prepare for the next run
            KeySourceImpl.pause = new CountDownLatch(1);
            KeySourceImpl.infinite = true;
            KeySourceImpl.lastCreated = null;
         }
         
         public String toString() { return "testOverlappingKeyStoreCalls"; }
         
         public void setup()
         {
            KeySourceImpl.pause = new CountDownLatch(1);
            KeySourceImpl.infinite = true;
            KeySourceImpl.lastCreated = null;
         }
      };
      
      runAllCombinations("SinglestageWithKeyStoreApplicationActx.xml",checker);
      runAllCombinations("SinglestageWithKeyStoreAndExecutorApplicationActx.xml",checker);
   }
   
   @Test
   public void testFailedMessageHandlingWithKeyStore() throws Throwable
   {
      final AtomicBoolean currentActivateCheckedException = new AtomicBoolean(false);
      
      Checker checker = new Checker()   
      {
         @Override
         public void check(ApplicationContext context) throws Throwable
         {
            // start things and verify that the init method was called
            Dempsy dempsy = (Dempsy)context.getBean("dempsy");
            TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");
                
            // verify we haven't called it again, not that there's really
            // a way to given the code
            assertEquals(1, mp.startCalls.get());
            
            // make sure that there are no Mps
            MetricGetters statsCollector = (MetricGetters)dempsy.getCluster(new ClusterId("test-app","test-cluster1")).getNodes().get(0).getStatsCollector();
            Thread.sleep(10);
            assertEquals(0,statsCollector.getMessageProcessorsCreated());
            
            mp.failActivation.set("test1");
            TestAdaptor adaptor = (TestAdaptor)context.getBean("adaptor");
            adaptor.pushMessage(new TestMessage("test1")); // this causes the container to clone the Mp

            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.cloneCalls.get()==1; } }));
            Thread.sleep(100);
            assertEquals(0,statsCollector.getMessageProcessorsCreated());
            
            mp.failActivation.set(null);
            KeySourceImpl.pause.countDown();

            // instead of the latch we are going to poll for the correct result
            // wait for it to be received.
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.cloneCalls.get()==3; } }));
            
            assertTrue(poll(baseTimeoutMillis,statsCollector,new Condition<MetricGetters>() { @Override public boolean conditionMet(MetricGetters mg) {  return mg.getMessageProcessorsCreated()==2; } }));
            adaptor.pushMessage(new TestMessage("test1"));
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.handleCalls.get()==1; } }));
            adaptor.pushMessage(new TestMessage("test2"));
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.handleCalls.get()==2; } }));
            adaptor.pushMessage(new TestMessage("test1"));
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.handleCalls.get()==3; } }));
            adaptor.pushMessage(new TestMessage("test2"));
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.handleCalls.get()==4; } }));
            adaptor.pushMessage(new TestMessage("test1"));
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.handleCalls.get()==5; } }));
            adaptor.pushMessage(new TestMessage("test2"));

            // instead of the latch we are going to poll for the correct result
            // wait for it to be received.
            assertTrue(poll(baseTimeoutMillis,mp,new Condition<TestMp>() { @Override public boolean conditionMet(TestMp mp) {  return mp.handleCalls.get()==6; } }));
            Thread.sleep(100);
            assertEquals(6,mp.handleCalls.get());
            assertEquals(3,mp.cloneCalls.get());
            assertEquals(2,statsCollector.getMessageProcessorsCreated());
            
            // prepare for the next run
            KeySourceImpl.pause = new CountDownLatch(1);
         }
         
         public String toString() { return "testFailedMessageHandlingWithKeyStore"; }
         
         public void setup() 
         {
            KeySourceImpl.pause = new CountDownLatch(1);
            TestMp.activateCheckedException = currentActivateCheckedException.get();
         }
      };

      // make sure both exceptions are handled since the logic in the container
      // actually varies depending on whether or not the exception is checked or not.
      currentActivateCheckedException.set(true);
      runAllCombinations("SinglestageWithKeyStoreApplicationActx.xml",checker);
      currentActivateCheckedException.set(false);
      runAllCombinations("SinglestageWithKeyStoreAndExecutorApplicationActx.xml",checker);
   }   
   
   @Test
   public void testExpandingAndContractingKeySpace() throws Throwable
   {
      Checker checker = new Checker()   
      {
         @Override
         public void check(ApplicationContext context) throws Throwable
         {
            ClusterInfoSession session = null;
            
            try
            {
               // start things and verify that the init method was called
               Dempsy dempsy = (Dempsy)context.getBean("dempsy");
               TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");
               final ClusterId clusterId = new ClusterId("test-app","test-cluster1");

               // verify we haven't called it again, not that there's really
               // a way to given the code
               assertEquals(1, mp.startCalls.get());

               // make sure that there are no Mps
               MetricGetters statsCollector = (MetricGetters)dempsy.getCluster(new ClusterId("test-app","test-cluster1")).getNodes().get(0).getStatsCollector();

               // This will wait until the keySpace is up to the maxcount which is set (in the setup, below) to 100000
               assertTrue(poll(baseTimeoutMillis, statsCollector, 
                     new Condition<MetricGetters>() { @Override public boolean conditionMet(MetricGetters sc) 
                     {  return 100000 == sc.getMessageProcessorCount(); } }));

               // now push the cluster into backup node.
               ClusterInfoSession originalSession = dempsy.getCluster(new ClusterId("test-app","test-cluster1")).getNodes().get(0).retouRteg().getClusterSession();
               ClusterInfoSessionFactory factory = dempsy.getClusterSessionFactory();

               session = TestUtils.stealShard(originalSession, factory, clusterId.asPath() + "/" + String.valueOf(0),baseTimeoutMillis);

               // If we got here then the MpContainer is on standby and the number of Mps should
               // drop to zero.
               assertTrue(poll(baseTimeoutMillis, statsCollector, 
                     new Condition<MetricGetters>() { @Override public boolean conditionMet(MetricGetters sc) 
                     { return 0 == sc.getMessageProcessorCount(); } }));
               Thread.sleep(10);
               assertEquals(0,statsCollector.getMessageProcessorCount());

               session.stop(); // this should give control back over to the original session.
               session = null;

               // If we got here then the MpContainer is no longer on standby and the number of Mps should
               // go back to the original amount.
               assertTrue(poll(baseTimeoutMillis, statsCollector, 
                     new Condition<MetricGetters>() { @Override public boolean conditionMet(MetricGetters sc) 
                     { return 100000 == sc.getMessageProcessorCount(); } }));
               Thread.sleep(10);
               assertEquals(100000,statsCollector.getMessageProcessorCount());
            }
            finally
            {
               if (session != null)
                  session.stop();
            }
         }
         
         public String toString() { return "testExpandingAndContractingKeySpace"; }
         
         public void setup() 
         {
            KeySourceImpl.maxcount = 100000;
            System.setProperty("min_nodes_for_cluster", "1");
            System.setProperty("total_slots_for_cluster", "1");
         }
      };

      runAllCombinations("SinglestageWithKeyStoreAndExecutorApplicationActx.xml",checker);
   }
   
   @Test
   public void testFailedClusterManagerDuringKeyStoreCalls() throws Throwable
   {
      Checker checker = new Checker()   
      {
         @Override
         public void check(ApplicationContext context) throws Throwable
         {
            ClusterInfoSession session = null;
            
            try
            {
               // start things and verify that the init method was called
               Dempsy dempsy = (Dempsy)context.getBean("dempsy");
               TestMp mp = (TestMp) getMp(dempsy, "test-app","test-cluster1");
               final ClusterId clusterId = new ClusterId("test-app","test-cluster1");

               // verify we haven't called it again, not that there's really
               // a way to given the code
               assertEquals(1, mp.startCalls.get());

               // make sure that there are no Mps
               MetricGetters statsCollector = (MetricGetters)dempsy.getCluster(new ClusterId("test-app","test-cluster1")).getNodes().get(0).getStatsCollector();

               assertTrue(poll(baseTimeoutMillis, statsCollector, 
                     new Condition<MetricGetters>() { @Override public boolean conditionMet(MetricGetters sc) 
                     {  return 100000 == sc.getMessageProcessorCount(); } }));
               
               // now there's 100000 mps in the container created from the KeySource. So we steal the 
               // shard and force if offline but continuously disrupt it while it tries to come
               // back up.

               // now push the cluster into backup node.
               ClusterInfoSession originalSession = dempsy.getCluster(new ClusterId("test-app","test-cluster1")).getNodes().get(0).retouRteg().getClusterSession();
               ClusterInfoSessionFactory factory = dempsy.getClusterSessionFactory();

               String path = clusterId.asPath() + "/" + String.valueOf(0);
               session = TestUtils.stealShard(originalSession, factory, path,baseTimeoutMillis);
               DefaultRouterSlotInfo si = (DefaultRouterSlotInfo)session.getData(path, null);
               assertTrue(si.getDestination() instanceof JunkDestination); // checks to see who actually has the slot.

               // we will keep disrupting the session but we should still end up with zero mps.
               for (int i = 0; i < 100; i++)
               {
                  ((DisruptibleSession)originalSession).disrupt();
                  Thread.sleep(1);
               }
               
               // now wait until we get to zero.
               assertTrue(poll(baseTimeoutMillis, statsCollector, 
                     new Condition<MetricGetters>() { @Override public boolean conditionMet(MetricGetters sc) 
                     { return 0 == sc.getMessageProcessorCount(); } }));
               Thread.sleep(10);
               assertEquals(0,statsCollector.getMessageProcessorCount());
               
               // ok. Now we will close the session that's holding the shard and allow the container
               // to re-establish control of that shard. During the KeyStore reinstantiation of the 
               // MPs we will be disrupting the session.
               session.stop();
               for (int i = 0; i < 100; i++)
               {
                  ((DisruptibleSession)originalSession).disrupt();
                  Thread.sleep(1);
               }

               // Now we should get back to 100000 Mps.
               poll(baseTimeoutMillis, statsCollector, 
                     new Condition<MetricGetters>() { @Override public boolean conditionMet(MetricGetters sc) 
                     { return 100000 == sc.getMessageProcessorCount(); } });

               assertEquals(100000,statsCollector.getMessageProcessorCount());
            }
            finally
            {
               if (session != null)
                  session.stop();
            }
         }
         
         public String toString() { return "testFailedClusterManagerDuringKeyStoreCalls"; }
         
         public void setup() 
         {
            KeySourceImpl.maxcount = 100000;
            System.setProperty("min_nodes_for_cluster", "1");
            System.setProperty("total_slots_for_cluster", "1");
         }
      };

      runAllCombinations("SinglestageWithKeyStoreAndExecutorApplicationActx.xml",checker);
   }
}
