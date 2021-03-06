/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2011, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.classic.spi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import ch.qos.logback.classic.util.TeztHelper;
import ch.qos.logback.core.util.SystemInfo;

public class PackagingDataCalculatorTest {

  public void verify(ThrowableProxy tp) {
    for (StackTraceElementProxy step : tp.getStackTraceElementProxyArray()) {
      if (step != null) {
        assertNotNull(step.getClassPackagingData());
      }
    }
  }

  @Test
  public void smoke() throws Exception {
    Throwable t = new Throwable("x");
    ThrowableProxy tp = new ThrowableProxy(t);
    PackagingDataCalculator pdc = tp.getPackagingDataCalculator();
    pdc.calculate(tp);
    verify(tp);
    tp.fullDump();
  }

  @Test
  public void nested() throws Exception {
    Throwable t = TeztHelper.makeNestedException(3);
    ThrowableProxy tp = new ThrowableProxy(t);
    PackagingDataCalculator pdc = tp.getPackagingDataCalculator();
    pdc.calculate(tp);
    verify(tp);
  }

  public void doCalculateClassPackagingData(
      boolean withClassPackagingCalculation) {
    try {
      throw new Exception("testing");
    } catch (Throwable e) {
      ThrowableProxy tp = new ThrowableProxy(e);
      if (withClassPackagingCalculation) {
        PackagingDataCalculator pdc = tp.getPackagingDataCalculator();
        pdc.calculate(tp);
      }
    }
  }

  double loop(int len, boolean withClassPackagingCalculation) {
    long start = System.nanoTime();
    for (int i = 0; i < len; i++) {
      doCalculateClassPackagingData(withClassPackagingCalculation);
    }
    return (1.0 * System.nanoTime() - start) / len / 1000;
  }

  @Test
  public void perfTest() {
    int len = 1000;
    loop(len, false);
    loop(len, true);

    double d0 = loop(len, false);
    System.out.println("without packaging info " + d0 + " microseconds");

    double d1 = loop(len, true);
    System.out.println("with    packaging info " + d1 + " microseconds");

    int slackFactor = 8;
    if (!SystemInfo.getJavaVendor().contains("Sun")) {
      // be more lenient with other JDKs
      slackFactor = 10;
    }
    assertTrue("computing class packaging data (" + d1
        + ") should have been less than " + slackFactor
        + " times the time it takes to process an exception "
        + (d0 * slackFactor), d0 * slackFactor > d1);

  }

  private ClassLoader makeBogusClassLoader() throws MalformedURLException {
    ClassLoader currentClassLoader = this.getClass().getClassLoader();
    BogusClassLoader bcl = new BogusClassLoader(new URL[] {},
        currentClassLoader);
    return bcl;
  }

  @Test
  // Test http://jira.qos.ch/browse/LBCLASSIC-125
  public void noClassDefFoundError_LBCLASSIC_125Test()
      throws MalformedURLException {
    ClassLoader cl = (URLClassLoader) makeBogusClassLoader();
    Thread.currentThread().setContextClassLoader(cl);
    Throwable t = new Throwable("x");
    ThrowableProxy tp = new ThrowableProxy(t);
    StackTraceElementProxy[] stepArray = tp.getStackTraceElementProxyArray();
    StackTraceElement bogusSTE = new StackTraceElement("com.Bogus", "myMethod",
        "myFile", 12);
    stepArray[0] = new StackTraceElementProxy(bogusSTE);
    PackagingDataCalculator pdc = tp.getPackagingDataCalculator();
    // NoClassDefFoundError should be caught
    pdc.calculate(tp);

  }

  @Test
  public void osgi_Test() throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
    boolean hasOsgi = false;
    for (Object strategy : getStrategiesViaReflection(getClass().getClassLoader())) {
      if (strategy.getClass().getName().toLowerCase(Locale.ENGLISH).contains("osgi"))
        hasOsgi = true;
    }
    assertTrue("strategies should include OSGi", hasOsgi);
  }
  @Test
  public void noOsgi_Test() throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
    // I want to test static initialization of the class, but WITHOUT any OSGi jars in the classpath
    // So make a new classloader just like the current one but without those jars and based on the boot loader
    List<URL> urls = new ArrayList<URL>(Arrays.asList(((URLClassLoader) getClass().getClassLoader()).getURLs()));
    for (Iterator<URL> it = urls.iterator(); it.hasNext(); ) {
      URL url = it.next();
      if (url.getPath().contains("felix") || url.getPath().contains("osgi"))
        it.remove();
    }
    ClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), null);// null means bootclassloader

    for (Object strategy : getStrategiesViaReflection(loader)) {
      assertFalse("strategy should not use OSGi: " + strategy.getClass().getName(),
              strategy.getClass().getName().toLowerCase(Locale.ENGLISH).contains("osgi"));
    }
  }

  // use Object[] instead of PackagingDataStrategy[] to avoid ClassCastException from other classloader
  private Object[] getStrategiesViaReflection(ClassLoader loader) throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
    Class<?> pdcClass = loader.loadClass(PackagingDataCalculator.class.getName());
    Field dataStrategiesField = pdcClass.getDeclaredField("dataStrategies");
    dataStrategiesField.setAccessible(true);
    return (Object[]) dataStrategiesField.get(null);
  }
}
