package com.splout.db.hadoop;

/*
 * #%L
 * Splout SQL Hadoop library
 * %%
 * Copyright (C) 2012 Datasalt Systems S.L.
 * %%
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
 * #L%
 */

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.datasalt.pangool.io.Fields;
import com.datasalt.pangool.io.Schema;
import com.datasalt.pangool.tuplemr.mapred.lib.input.TupleTextInputFormat;
import com.datasalt.pangool.utils.HadoopUtils;
import com.splout.db.common.SploutHadoopConfiguration;
import com.splout.db.engine.DefaultEngine;
import com.splout.db.hadoop.TupleSampler.SamplingType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * This tool can be used for creating and deploying a single-table tablespace from text files. Use command-line
 * parameters for definiting your Tablespace. For a more general multi-tablespace generator use {@link GeneratorCMD}.
 */
public class SimpleGeneratorCMD implements Tool {

  private final static Log log = LogFactory.getLog(SimpleGeneratorCMD.class);

  @Parameter(required = true, names = {"-tb", "--tablespace"}, description = "The tablespace name.")
  private String tablespace;

  @Parameter(required = true, names = {"-t", "--table"}, description = "The table name.")
  private String tablename;

  @Parameter(names = {"-i", "--input"}, description = "Input path/glob containing data to use. If you are running the process from Hadoop, relative paths would use the Hadoop filesystem. Use full qualified URIs instead if you want other behaviour.")
  private String input;

  @Parameter(required = true, names = {"-o", "--output"}, description = "Output path where the view will be saved. If you are running the process from Hadoop, relative paths would use the Hadoop filesystem. Use full qualified URIs instead if you want other behaviour.")
  private String output;

  @Parameter(names = {"-s", "--schema"}, description = "Input schema, in Pangool in-line format. Mandatory when using Textual input, optional if the input files are binary Tuple files that already contain a schema. Fields are comma-sepparated. Example: \"id:int,name:string,salary:long\"")
  private String schema;

  @Parameter(names = {"-it", "--inputType"}, description = "The input type (TEXT by default). Other support types are: TUPLE, CASCADING, HIVE, HCATALOG")
  private InputType inputType = InputType.TEXT;

  @Parameter(names = {"-cc", "--cascadingColumns"}, description = "When using input type CASCADING, one must provide a comma-separated column name list for interpreting the binary Cascading Tuples. These names will be used for the resulting Splout table.")
  private String cascadingColumns;

  @Parameter(names = {"-hdb", "--hiveDbName"}, description = "When using input type HIVE, both db name and table name must be provided using appropriated arguments.")
  private String hiveDbName;

  @Parameter(names = {"-htn", "--hiveTableName"}, description = "When using input type HIVE, both db name and table name must be provided using appropriated arguments.")
  private String hiveTableName;

  @Parameter(required = true, names = {"-pby", "--partitionby"}, description = "The field or fields to partition the table by. Comma-sepparated if there is more than one.")
  private String partitionByFields;

  @Parameter(names = {"-idx", "--index"}, description = "The fields to index. More than one allowed. Comma-sepparated in the case of a multifield index.")
  private List<String> indexes = new ArrayList<String>();

  @Parameter(required = true, names = {"-p", "--partitions"}, description = "The number of partitions to create for the view.")
  private Integer nPartitions;

  @Parameter(converter = CharConverter.class, names = {"-sep", "--separator"}, description = "The separator character of your text input file, defaults to a tabulation")
  private Character separator = '\t';

  @Parameter(converter = CharConverter.class, names = {"-quo", "--quotes"}, description = "The quotes character of your input file, defaults to none.")
  private Character quotes = TupleTextInputFormat.NO_QUOTE_CHARACTER;

  @Parameter(converter = CharConverter.class, names = {"-esc", "--escape"}, description = "The escape character of your input file, defaults to none.")
  private Character escape = TupleTextInputFormat.NO_ESCAPE_CHARACTER;

  @Parameter(names = {"-sh", "--skipheading"}, description = "Specify this flag for skipping the header line of your text file.")
  private boolean skipHeading = false;

  @Parameter(names = {"-sq", "--strictquotes"}, description = "Activate strict quotes mode where all values that are not quoted are considered null.")
  private boolean strictQuotes = false;

  @Parameter(names = {"-ns", "--nullstring"}, description = "A string sequence which, if found and not quoted, it's considered null. For example, \\N in mysqldumps.")
  private String nullString = TupleTextInputFormat.NO_NULL_STRING;

  @Parameter(names = {"-fw", "--fixedwidthfields"}, description = "When used, you must provide a comma-separated list of numbers. These numbers will be interpreted by pairs, as [beginning, end] inclusive position offsets. For example: 0,3,5,7 means there are two fields, the first one of 4 characters at offsets [0, 3] and the second one of 3 characters at offsets [5, 7]. This option can be used in combination with --nullstring parameter. The rest of CSV parameters are ignored.")
  private String fixedWidthFields;

  @Parameter(names = {"-st", "--samplingType"}, description = "It can be used to specify the sampling method to use. FULL_SCAN executes a map-only Job before generation but it is more accurate. This is the recommended one. RANDOM is very simple and fast, but might not be accurate.")
  private SamplingType samplingType = SamplingType.FULL_SCAN;

  @Parameter(names = {"-e", "--engine"}, description = "Specify the engine to be used for generating the tablespace.")
  private String engine = DefaultEngine.class.getName();

  public static class CharConverter implements IStringConverter<Character> {

    @Override
    public Character convert(String value) {
      // Because space gets trimmed by JCommander we recover it here
      return value.length() == 0 ? ' ' : value.toCharArray()[0];
    }
  }

  private Configuration conf;

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public int run(String[] args) throws Exception {
    JCommander jComm = new JCommander(this);
    jComm.setProgramName("Splout Tablespace View Generator");
    try {
      jComm.parse(args);
    } catch (Throwable t) {
      t.printStackTrace();
      jComm.usage();
      return -1;
    }

    log.info("Parsing input parameters...");

    // because Schema is now optional (it can be derived from Cascading or Hive tables),
    // check that it is provided when using TEXT as input.
    if (this.inputType.equals(InputType.TEXT) && this.schema == null) {
      System.err.println("A Pangool Schema must be provided for parsing text files.");
      jComm.usage();
      return -1;
    }

    // all input types except Hive require input paths
    if (!this.inputType.equals(InputType.HIVE) && this.input == null) {
      System.err.println("Input path must be provided.");
      jComm.usage();
      return -1;
    }

    // validate we have all needed args for Cascading integration
    if (this.inputType.equals(InputType.CASCADING) && this.cascadingColumns == null) {
      System.err
          .println("A comma-separated list of column names must be provided for reading Cascading Tuple files.");
      jComm.usage();
      return -1;
    }

    // validate we have all needed args for Hive integration
    if (this.inputType.equals(InputType.HIVE) && (this.hiveDbName == null || this.hiveTableName == null)) {
      System.err
          .println("When using input type HIVE, both db name and table name must be provided using appropriated arguments.");
      jComm.usage();
      return -1;
    }

    TableBuilder tableBuilder;

    Schema schema = null;

    if (this.schema != null) {
      schema = new Schema(tablename, Fields.parse(this.schema));
      tableBuilder = new TableBuilder(schema);
    } else {
      tableBuilder = new TableBuilder(tablename, conf);
    }

    Path out = new Path(output, tablespace);
    FileSystem outFs = out.getFileSystem(getConf());
    HadoopUtils.deleteIfExists(outFs, out);

    if (!FileSystem.getLocal(conf).equals(FileSystem.get(conf))) {
      File nativeLibs = new File("native");
      if (nativeLibs.exists()) {
        SploutHadoopConfiguration.addSQLite4JavaNativeLibsToDC(conf);
      }
    }

    int fixedWidth[] = null;
    if (fixedWidthFields != null) {
      String[] posStr = fixedWidthFields.split(",");
      fixedWidth = new int[posStr.length];
      for (int i = 0; i < posStr.length; i++) {
        try {
          fixedWidth[i] = new Integer(posStr[i]);
        } catch (NumberFormatException e) {
          System.err.println("Wrongly formed fixed with field list: [" + fixedWidthFields + "]. "
              + posStr[i] + " does not look as a number.");
          return -1;
        }
      }
    }

    TablespaceBuilder builder = new TablespaceBuilder();
    builder.setEngineClassName(engine);
    log.info("Using engine: " + engine);

    if (inputType.equals(InputType.TEXT)) {
      Path inputPath = new Path(input);
      if (fixedWidth == null) {
        // CSV
        tableBuilder.addCSVTextFile(inputPath, separator, quotes, escape, skipHeading, strictQuotes,
            nullString);
      } else {
        // Fixed Width
        tableBuilder.addFixedWidthTextFile(inputPath, schema, fixedWidth, skipHeading, nullString, null);
      }
    } else if (inputType.equals(InputType.TUPLE)) {
      // Pangool Tuple file
      Path inputPath = new Path(input);
      if (schema != null) {
        tableBuilder.addTupleFile(inputPath, schema);
      } else {
        tableBuilder.addTupleFile(inputPath);
      }
    } else if (inputType.equals(InputType.CASCADING)) {
      // Cascading Tuple file
      Path inputPath = new Path(input);
      tableBuilder.addCascadingTable(inputPath, cascadingColumns.split(","));
    } else if (inputType.equals(InputType.HIVE)) {
      // Hive table
      tableBuilder.addHiveTable(hiveDbName, hiveTableName);
    }

    String[] strPartitionByFields = this.partitionByFields.split(",");
    tableBuilder.partitionBy(strPartitionByFields);

    for (String strIndex : indexes) {
      tableBuilder.createIndex(strIndex.split(","));
    }

    log.info("Generating view with Hadoop...");

    builder.add(tableBuilder.build());
    builder.setNPartitions(nPartitions);

    TablespaceGenerator viewGenerator = new TablespaceGenerator(builder.build(), out, this.getClass());
    viewGenerator.generateView(conf, samplingType, new TupleSampler.RandomSamplingOptions());

    log.info("Success! Tablespace [" + tablespace + "] with table [" + tablename
        + "] properly created at path [" + out + "]");
    return 0;
  }

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new SimpleGeneratorCMD(), args);
  }
}