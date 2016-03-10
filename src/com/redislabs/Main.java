package com.redislabs;

import com.redislabs.research.*;
import com.google.gson.Gson;
import com.redislabs.research.Engine;
import com.redislabs.research.redis.Encoders;
import com.redislabs.research.redis.JSONStore;
import com.redislabs.research.redis.PartitionedIndex;
import com.redislabs.research.redis.SimpleIndex;
import org.apache.commons.cli.*;

import javax.print.Doc;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


/**
 * Created by dvirsky on 21/02/16.
 */
public class Main {


    public static class Benchmark_SearchByName extends Benchmark {

        private Engine engine;
        String fileName;
        String[] redisHosts;
        int numPartitions;
        boolean loadData;
        String which;

        List<String> queries;

        public Benchmark_SearchByName(String[] args) throws IOException {

            super(args,

                    new Option("l", "load", false, "Only load data if set to true"),
                    new Option("f", "file", true, "input file to load"),
                    new Option("q", "queriesFile", true, "input file for benchmarkQueries"),
                    new Option("p", "partitions", true, "number of index partitions"),

                    Option.builder("r").longOpt("redisHosts").hasArgs()
                            .argName("localhost:6379,...").desc("redis hosts, comma separated")
                            .build(),

                    Option.builder("w").longOpt("which").hasArgs()
                            .argName("benchmark")
                            .desc("which benchmark to run [by_name|by_geo|by_name_geo]")
                            .build()
            );

            this.fileName = getOption("file", "");
            this.loadData = isOptionSet("load");
            this.numPartitions = Integer.parseInt(getOption("partitions", "8"));
            this.which = getOption("which", "by_name");
            this.tag = this.which + (this.tag.isEmpty() ? "" : " / " + this.tag);


            String[] hosts = getOption("redisHosts", "localhost:6379").split(",");
            this.redisHosts = new String[hosts.length];
            int i = 0;
            for (String h : hosts) {
                this.redisHosts[i++] = String.format("redis://%s", h);
            }

            loadQueries();
            initEngine();
        }

        private void loadQueries() throws IOException {

            String qfile = getOption("queriesFile", "");
            if (qfile != null && !qfile.isEmpty()) {
                queries = new ArrayList<>(100);

                Path path = FileSystems.getDefault().getPath("", qfile);

                BufferedReader rd = Files.newBufferedReader(path, Charset.forName("utf-8"));

                String line = null;

                while ((line = rd.readLine()) != null) {
                    queries.add(line);
                }
                System.out.printf("Loaded %d queries\n", queries.size());
            } else {

                queries = Arrays.asList(new String[]{"a", "b", "c", "ab", "ac", "ca", "do", "ma", "foo", "bar", "ax"});

            }


        }

        /**
         * Init the engine and indexes, and configure everthing
         * @throws IOException
         */
        private void initEngine() throws IOException {

            // create an index by name only
            Index nameIndex = new PartitionedIndex(new SimpleIndex.Factory(), "nm",
                    new Spec(Spec.prefix("name", false)),
                    numPartitions,
                    500,
                    numThreads*numPartitions,
                    redisHosts);

            // create an index by latlon and name
            Index nameCityIndex =    new PartitionedIndex(new SimpleIndex.Factory(), "nmg",
                    new Spec(Spec.geo("latlon", Encoders.Geohash.PRECISION_40KM), Spec.prefix("name", false)),
                    numPartitions,
                    500,
                    numThreads*numPartitions,
                    redisHosts);


            // create the document store
            DocumentStore st = new JSONStore(redisHosts[0]);

            // bind everything in the engine
            engine = new Engine(st, nameIndex, nameCityIndex);

            // if we need to - load the data
            if (loadData && !fileName.isEmpty()) {
                System.out.println("Loading data from " + fileName);
                nameIndex.drop();
                nameCityIndex.drop();
                load(fileName, engine);
            }

        }


        /**
         * Load all the raw data from geojson dump files to the engine
         * @param fileName
         * @param engine
         * @throws IOException
         */
        void load(String fileName, Engine engine) throws IOException {


            Path path = FileSystems.getDefault().getPath("", fileName);
            BufferedReader rd =
                    Files.newBufferedReader(path, Charset.forName("utf-8"));

            String line = null;
            Gson gson = new Gson();
            int i = 0;
            int CHUNK = 10000;
            Document[] docs = new Document[CHUNK];
            while ((line = rd.readLine()) != null) {

                GeoJsonEntity ent = gson.fromJson(line, GeoJsonEntity.class);

                Document doc = new Document(ent.id);
                doc.set("name", ent.properties.name);
                doc.set("city", ent.properties.city);
                // we need to reverse the lat,lon
                doc.set("latlon", new Double[]{ent.geometry.coordinates[1], ent.geometry.coordinates[0]});

                docs[i % CHUNK] = doc;


                if (i % CHUNK == CHUNK - 1) {
                    engine.put(docs);
                    System.out.println(i);
                }
                i++;

            }

            if (i % CHUNK != 0) {
                docs = Arrays.copyOfRange(docs, 0, i % CHUNK);
                engine.put(docs);
            }


        }

        /**
         * Run a benchmark, searching only by business name
         * @param ctx
         */
        private void runByName(Context ctx) {
            int sz = queries.size();
            int x = 0;
            do {
                List<Document> docs = engine.search(new Query("nm").filterPrefix("name", queries.get(x % sz)));
                ++x;
            } while (ctx.tick());

        }

        /**
         * Run a benchmark, searching by name and location
         * @param ctx
         */
        private void runByNameAndLocation(Context ctx) {

            double[][] locations = new double[][] { {33.914829, -118.203708}, {47.594855, -122.317372}, {60.661282,-151.288153}, {40.762475, -73.978873}, {37.776912, -122.416760}, {33.758042, -84.394902}};
            int sz = queries.size();
            int x = 0;

            do {
                engine.search(new Query("nmg").filterNear("latlon", locations[x%locations.length]).filterPrefix("name", queries.get(x % sz)));
                ++x;
            } while (ctx.tick());

        }

        /**
         * Run a benchmark, searching by location only, around a bunch of random locations in us cities
         * @param ctx
         */
        private void runByLocation(Context ctx) {

            double[][] locations = new double[][] { {33.914829, -118.203708}, {47.594855, -122.317372}, {60.661282,-151.288153}, {40.762475, -73.978873}, {37.776912, -122.416760}, {33.758042, -84.394902}};
            int x = 0;
            do {
                 engine.search(new Query("nmg").filterNear("latlon", locations[x%locations.length]));
                ++x;
            } while (ctx.tick());

        }
        @Override
        public void run(Context ctx) {

            switch (which) {
                //run name/geo benchmark
                case "by_name_geo":
                    runByNameAndLocation(ctx);
                    break;

                // run geo only benchmark
                case "by_geo":
                    runByLocation(ctx);
                    break;

                // run name only benchmark
                case "by_name":
                default:
                    runByName(ctx);
            }


        }
    }


    public static void main(String[] args) throws IOException {
        Benchmark sb = new Benchmark_SearchByName(args);
        sb.start();
        System.out.println("That's all folks!");
        System.exit(0);
    }
}
