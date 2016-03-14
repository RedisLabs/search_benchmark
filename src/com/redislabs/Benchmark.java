package com.redislabs;

import org.apache.commons.cli.*;
import org.apache.commons.math3.stat.StatUtils;

import java.io.PrintStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by dvirsky on 10/03/16.
 */
public abstract class Benchmark {
    public int numThreads;
    public long runDuration;
    public String tag;
    public int numTests;
    public double sampleRate;

    public static class Flags {


        protected Options opts;
        protected CommandLine cmd;


        public Flags(Option... extraOptions) {

            opts = new Options();

            opts.addOption(new Option("h", "help", false, "print this message"));
            opts.addOption(new Option("T", "tag", true, "tag this benchmark for file output (optional)"));

            opts.addOption(Option.builder("d")
                    .longOpt("duration")
                    .hasArgs()
                    .type(Long.class)
                    .argName("seconds")
                    .desc("Benchmark run duration, in seconds")
                    .build());

            opts.addOption(Option.builder("t")
                    .longOpt("threads")
                    .hasArgs()
                    .argName("num")
                    .type(Integer.class)
                    .desc("Benchmark run duration, in seconds")
                    .build());

            opts.addOption(Option.builder("s")
                    .longOpt("sampleRate")
                    .hasArgs()
                    .argName("num")
                    .type(Double.class)
                    .desc("Timing sample rate")
                    .build());

            for (Option opt : extraOptions) {
                opts.addOption(opt);
            }

        }


        public void parse(String[] args) {

            CommandLineParser parser = new DefaultParser();

            try {
                cmd = parser.parse(opts, args);

                if (cmd.hasOption("help")) {

                    HelpFormatter formatter = new HelpFormatter();
                    formatter.printHelp("java -jar research.jar", opts);

                    System.exit(0);
                }
            } catch (ParseException e) {
                System.err.println(e.toString());

            }


        }
    }


    public interface Context {
        boolean tick();
    }

    private class ParallelContext implements Context {

        AtomicLong numTicks;
        AtomicLong lastCheckpointTime;
        AtomicLong lastCheckpointTicks;

        AtomicBoolean isRunning;

        private AtomicLong startTime;
        private final long durationMS;
        private final long checkInterval;
        private final String name;
        private List<Double> timeSamples;
        private AtomicLong endTime;


        public ParallelContext(String benchmarkName, long durationMS, long checkInterval) {

            this.durationMS = durationMS;
            name = benchmarkName;
            this.checkInterval = checkInterval;
            startTime = new AtomicLong(System.currentTimeMillis());
            numTicks = new AtomicLong(0);
            endTime = new AtomicLong(0);
            lastCheckpointTime = new AtomicLong(0);
            lastCheckpointTicks = new AtomicLong(0);
            timeSamples = new LinkedList<>();
            isRunning = new AtomicBoolean(true);
        }

        private double rate(long ticks, long duration) {
            return (double) ticks / (duration / 1000d);
        }

        private void printStats(long ticks, long now, PrintStream out) {
            Timestamp ts = new Timestamp(now);
            out.printf("%s> %s: %d iterations, current rate: %.02fops/sec, avg. rate: %.02fops/sec\n",
                    ts.toString(), name, ticks,
                    rate(ticks - lastCheckpointTicks.get(), now - lastCheckpointTime.get()),
                    rate(ticks, now - startTime.get())
            );
        }

        private boolean onCheckpoint(long ticks) {
            long now = System.currentTimeMillis();

            // if too long a time has passed - stop
            if (startTime.get() + durationMS <= now) {
                endTime.set(now);
                isRunning.set(false);

                return false;
            }
            System.out.print('.');
            System.out.flush();
            printStats(ticks, now, System.out);
            lastCheckpointTime.set(now);
            lastCheckpointTicks.set(ticks);
            return true;
        }

        public boolean tick() {


            long ticks = this.numTicks.incrementAndGet();

            if (ticks == 1) {
                startTime.set(System.currentTimeMillis());
                lastCheckpointTime.set(startTime.get());
            }
            if (ticks % checkInterval == 0) {
                return onCheckpoint(ticks);
            }

            return isRunning.get();
        }

        public synchronized void addSample(double time) {

            timeSamples.add(time);
        }


        public void printSummary(PrintStream out) {
            double[] samples = new double[timeSamples.size()];
            int i = 0;
            for (Double s : timeSamples) {
                samples[i] = s;
                i++;
            }
            long now = endTime.get();
            Timestamp ts = new Timestamp(now);
            out.println("\n\nSummary:\n-------------------------\n");
            out.println("Benchmark: " + name);
            out.printf("Threads: %d\n", numThreads);
            out.printf("Ran for %d seconds, finished at %s\n", (endTime.get()-startTime.get())/1000, ts.toString());
            out.printf("Iterations: %d\n", numTicks.get());
            out.printf("Average rate: %.02f ops/sec\n\n", rate(numTicks.get(), now - startTime.get()));
            out.printf("Latency:\n" +
                    "\t- Average: %.02f ops/sec\n" +
                    "\t- Median: %.02fms\n" +
                    "\t- 99th Percentile: %.02fms\n"  +
                    "\t- 95th Percentile: %.02fms\n"  +
                    "\t- 90th Percentile: %.02fms\n",
                    StatUtils.mean(samples),
                    StatUtils.percentile(samples, 50d),
                    StatUtils.percentile(samples, 99d),
                    StatUtils.percentile(samples, 95d),
                    StatUtils.percentile(samples, 90d)

            );



//            for (double pct = 100; pct >= 10; pct-=10) {
//
//                out.printf("%d%%: %.02fms\n", (int)pct, StatUtils.percentile(samples, pct));
//
//            }
        }


    }

    private class ThreadLocalContext implements Context {

        private final double sampleRate;
        private ParallelContext parent;

        public ThreadLocalContext(ParallelContext parent, double sampleRate) {
            this.parent = parent;
            this.lastSample = 0;
            this.sampleRate = sampleRate;
        }

        private long lastSample;


        public boolean tick() {

            long now = System.nanoTime();

            if (Math.random() <= sampleRate && lastSample > 0) {

                parent.addSample((now - lastSample) / 1000000d);

            }
            lastSample = now;
            return parent.tick();
        }

    }


    protected Flags flags;

    public Benchmark(String[] argv, Option... extraOptions) {

        flags = new Flags(extraOptions);
        flags.parse(argv);

        this.numThreads = Integer.parseInt(getOption("threads", "1"));
        this.runDuration = Integer.parseInt(getOption("duration", "60"));
        this.tag = getOption("tag", "");
        this.sampleRate = Double.parseDouble(getOption("sampleRate", "0.01"));
    }

    public abstract void run(Context ctx);


    protected String getOption(String name, String defaultValue) {
        return flags.cmd.getOptionValue(name, defaultValue);
    }

    protected String getOption(String name) {
        return flags.cmd.getOptionValue(name);
    }

    protected boolean isOptionSet(String name) {
        return flags.cmd.hasOption(name);
    }

    public void start() {

        System.out.printf("Benchmarking %s using %d threads\n", getClass().getSimpleName() + " [" + tag + "]", numThreads);
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);


        final ParallelContext ctx = new ParallelContext(tag, runDuration * 1000, 5000);
        for (int i = 0; i < numThreads; i++) {

            pool.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    try {
                        Benchmark.this.run(new ThreadLocalContext(ctx, sampleRate));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    return null;
                }
            });

        }


        try {
            pool.awaitTermination(runDuration + 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        ctx.printSummary(System.out);
        System.out.println("Benchmark finished!");


    }
}
