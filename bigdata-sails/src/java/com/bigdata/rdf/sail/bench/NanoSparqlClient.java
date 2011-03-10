/*

Copyright (C) SYSTAP, LLC 2006-2010.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/
/*
 * Created on May 29, 2010
 */
package com.bigdata.rdf.sail.bench;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.openrdf.query.parser.ParsedQuery;
import org.openrdf.query.parser.QueryParser;
import org.openrdf.query.parser.sparql.SPARQLParserFactory;

/**
 * A flyweight utility for issuing queries to an http SPARQL endpoint.
 * 
 * @author thompsonbry@users.sourceforge.net
 */
public class NanoSparqlClient {

	private static final Logger log = Logger.getLogger(NanoSparqlClient.class);
	
	/**
	 * A SPARQL results set in XML.
	 */
	static final String MIME_SPARQL_RESULTS_XML = "application/sparql-results+xml";
	/**
	 * RDF/XML.
	 */
	static final String MIME_RDF_XML = "application/rdf+xml";

	/**
	 * The default connection timeout (ms).
	 */
	static private final int DEFAULT_TIMEOUT = 60*1000;

	/**
	 * Helper class to figure out the type of a query.
	 */
	public static enum QueryType {
		
		ASK(0),
		DESCRIBE(1),
		CONSTRUCT(2),
		SELECT(3);
		
		private final int order;
		
		private QueryType(final int order) {
			this.order = order;
		}

		private static QueryType getQueryType(final int order) {
			switch (order) {
			case 0:
				return ASK;
			case 1:
				return DESCRIBE;
			case 2:
				return CONSTRUCT;
			case 3:
				return SELECT;
			default:
				throw new IllegalArgumentException("order=" + order);
			}
		}

		/**
		 * Used to note the offset at which a keyword was found. 
		 */
		static private class P implements Comparable<P> {

			final int offset;
			final QueryType queryType;

			public P(final int offset, final QueryType queryType) {
				this.offset = offset;
				this.queryType = queryType;
			}
			/** Sort into descending offset. */
			public int compareTo(final P o) {
				return o.offset - offset;
			}
		}

		/**
		 * Hack returns the query type based on the first occurrence of the
		 * keyword for any known query type in the query.
		 * 
		 * @param queryStr
		 *            The query.
		 * 
		 * @return The query type.
		 */
		static public QueryType fromQuery(final String queryStr) {

			// force all to lower case.
			final String s = queryStr.toUpperCase();

			final int ntypes = QueryType.values().length;

			final P[] p = new P[ntypes];

			int nmatch = 0;
			for (int i = 0; i < ntypes; i++) {

				final QueryType queryType = getQueryType(i);
				
				final int offset = s.indexOf(queryType.toString());

				if (offset == -1)
					continue;

				p[nmatch++] = new P(offset, queryType); 
				
			}

			if (nmatch == 0) {

				throw new RuntimeException(
						"Could not determine the query type: " + queryStr);

			}

			Arrays.sort(p, 0/* fromIndex */, nmatch/* toIndex */);

			final P tmp = p[0];

//			System.out.println("QueryType: offset=" + tmp.offset + ", type="
//					+ tmp.queryType);

			return tmp.queryType;

		}
		
	}
	
	/**
	 * Class submits a SPARQL query using httpd and writes the result on stdout.
	 */
	static public class Query implements Callable<Long> {

//		private final HttpClient client;
		final QueryOptions opts;

		/**
		 * 
		 * @param opts The query options.
		 */
		public Query(/* HttpClient client, */ final QueryOptions opts) {

			if (opts == null)
				throw new IllegalArgumentException();
			
			// this.client = client;
			this.opts = opts;

		}

		public Long call() throws Exception {

			// used to measure the total execution time.
			final long begin = System.nanoTime();

			/*
			 * Parse the query so we can figure out how it will need to be
			 * executed.
			 * 
			 * Note: This will fail a query on its syntax. However, the logic
			 * used in the tasks to execute a query will not fail a bad query
			 * for some reason which I have not figured out yet.
			 */
			final QueryParser engine = new SPARQLParserFactory().getParser();
			
			final ParsedQuery q = engine.parseQuery(opts.queryStr, null/*baseURI*/);

			if (opts.showQuery) {
				System.err.println("---- Original Query ----");
				System.err.println(opts.queryStr);
				System.err.println("----- Parsed Query -----");
				System.err.println(q.toString());
			}
			
			// Fully formed and encoded URL @todo use */* for ASK.
			final String urlString = opts.serviceURL
					+ "?query="
					+ URLEncoder.encode(opts.queryStr, "UTF-8")
					+ (opts.defaultGraphUri == null ? ""
							: ("&default-graph-uri=" + URLEncoder.encode(
									opts.defaultGraphUri, "UTF-8")));
			
//			// Note:In general GET caches but is more transparent while POST
//			// does not cache. @todo disable caching ?
//			final HttpMethod method = new GetMethod(url);
			final URL url = new URL(urlString);
			HttpURLConnection conn = null;
			try {

				/*
				 * @todo review connection properties.
				 */
				conn = (HttpURLConnection) url.openConnection();
				conn.setRequestMethod("GET");
				conn.setDoOutput(true);
				conn.setUseCaches(false);
				conn.setReadTimeout(opts.timeout);

				/*
				 * Set an appropriate Accept header for the query.
				 */
				final QueryType queryType = QueryType.fromQuery(opts.queryStr);
				
				switch(queryType) {
				case DESCRIBE:
				case CONSTRUCT:
					conn.setRequestProperty("Accept", MIME_RDF_XML);
					break;
				case SELECT:
					conn.setRequestProperty("Accept", MIME_SPARQL_RESULTS_XML);
					break;
				}

				// write out the request headers
				if (log.isDebugEnabled()) {
					log.debug("*** Request ***");
					log.debug(opts.serviceURL);
					log.debug(opts.queryStr);
				}

//				System.out.println("Request Path: " + url);
//				System.out.println("Request Query: " + method.getQueryString());
//				Header[] requestHeaders = method.getRequestHeaders();
//				for (int i = 0; i < requestHeaders.length; i++) {
//					System.out.print(requestHeaders[i]);
//				}
//
//				// execute the method
//				client.executeMethod(method);

				// connect.
				conn.connect();

				final int rc = conn.getResponseCode();
					if(rc < 200 || rc >= 300) {
                    throw new IOException(rc + " : "
                            + conn.getResponseMessage()+" : "+url);
				}

				if (log.isDebugEnabled()) {
					/*
					 * write out the response headers
					 * 
					 * @todo options to show the headers (in/out),
					 */
					log.debug("*** Response ***");
					log.debug("Status Line: " + conn.getResponseMessage());
				}

				/*
				 * Write out the response body
				 * 
				 * @todo option to write the results, count the results, etc.
				 */
				{

					final LineNumberReader r = new LineNumberReader(
							new InputStreamReader(
									conn.getInputStream(),
									conn.getContentEncoding() == null ? "ISO-8859-1"
											: conn.getContentEncoding()));
					try {
						String s;
						while ((s = r.readLine()) != null) {
							if(!opts.hideResults)
								System.out.println(s);
						}
					} finally {
						r.close();
					}

				}

				final long elapsed = System.nanoTime() - begin;

				return Long.valueOf(elapsed);

			} finally {

				// clean up the connection resources
				// method.releaseConnection();
				if (conn != null)
					conn.disconnect();

			}

		}

	}

    /**
     * Read the contents of a file.
     * <p>
     * Note: This makes default platform assumptions about the encoding of the
     * file.
     * 
     * @param file
     *            The file.
     * @return The file's contents.
     * 
     * @throws IOException
     */
    static private String readFromFile(final File file) throws IOException {

		if (file.isDirectory())
			throw new IllegalArgumentException();
    	
        final LineNumberReader r = new LineNumberReader(new FileReader(file));

        try {

            final StringBuilder sb = new StringBuilder();

            String s;
            while ((s = r.readLine()) != null) {

                if (r.getLineNumber() > 1)
                    sb.append("\n");

                sb.append(s);

            }

            return sb.toString();

        } finally {

            r.close();

        }

    }

    /**
     * Read from stdin.
     * <p>
     * Note: This makes default platform assumptions about the encoding of the
     * data being read.
     * 
     * @return The data read.
     * 
     * @throws IOException
     */
    static private String readFromStdin() throws IOException {

        final LineNumberReader r = new LineNumberReader(new InputStreamReader(System.in));

        try {

            final StringBuilder sb = new StringBuilder();

            String s;
            while ((s = r.readLine()) != null) {

                if (r.getLineNumber() > 1)
                    sb.append("\n");

                sb.append(s);

            }

            return sb.toString();

        } finally {

            r.close();

        }

    }

	/**
	 * Populate the list with the plain text files (recursive search of a file
	 * or directory).
	 * 
	 * @param fileOrDir
	 *            The file or directory.
	 * @param fileList
	 *            The list to be populated.
	 */
	static private void getFiles(final File fileOrDir,
			final List<File> fileList) {

		if (fileOrDir.isHidden())
			return;

		if (fileOrDir.isDirectory()) {

			final File dir = fileOrDir;

			final File[] files = dir.listFiles();

			for (int i = 0; i < files.length; i++) {

				final File f = files[i];

				// recursion.
				getFiles(f, fileList);

			}

		} else {

			fileList.add(fileOrDir);

		}

    }

	/**
	 * Read queries from each file in the given list.
	 * 
	 * @param fileList
	 *            The list of files.
	 * 
	 * @return An array of the queries read from that list.
	 * 
	 * @throws IOException
	 */
	static private String[] readQueries(final List<File> fileList)
			throws IOException {

		final List<String> queries = new LinkedList<String>();

		for (File file : fileList) {

			queries.add(readFromFile(file));

		}

		return queries.toArray(new String[queries.size()]);

	}

	/**
	 * Helper produces a random sequence of indices in the range [0:n-1]
	 * suitable for visiting the elements of an array of n elements in a random
	 * order. This is useful when you want to randomize the presentation of
	 * elements from two or more arrays. For example, known keys and values can
	 * be generated and their presentation order randomized by indexing with the
	 * returned array.
	 */
	private static int[] getRandomOrder(final long seed, final int n) {

		final Random rnd = new Random(seed);
		
		final class Pair implements Comparable<Pair> {
			public double r = rnd.nextDouble();
			public int val;

			public Pair(int val) {
				this.val = val;
			}

			public int compareTo(final Pair other) {
				if (this == other)
					return 0;
				if (this.r < other.r)
					return -1;
				else
					return 1;
			}

		}

		final Pair[] pairs = new Pair[n];

		for (int i = 0; i < n; i++) {

			pairs[i] = new Pair(i);

		}

		java.util.Arrays.sort(pairs);

		final int order[] = new int[n];

		for (int i = 0; i < n; i++) {

			order[i] = pairs[i].val;

		}

		return order;

	}
    
    /**
	 * Options for the query.
	 */
	public static class QueryOptions {

		/** The URL of the SPARQL endpoint. */
		public String serviceURL = null;
		public String username = null;
		public String password = null;
		/** The SPARQL query. */
		public String queryStr;
		/** The default graph URI (optional). */
		public String defaultGraphUri = null;
		/** The connection timeout (ms). */
		public int timeout = DEFAULT_TIMEOUT;
		/** When <code>true</code>, show the original and parsed query. */
		public boolean showQuery = false;
		/** When <code>true</code>, do not show the results of the query. */
		public boolean hideResults = false;
        public boolean verbose = false;
        public boolean quiet = false;

        /**
         * The query is not specified to the constructor must be set explicitly
         * by the caller.
         */
        public QueryOptions() {

            this(null/*serviceURL*/,null/*queryStr*/);
            
        }

        /**
         * @param serviceURL
         *            The SPARQL end point URL.
         * @param queryStr
         *            The SPARQL query.
         */
        public QueryOptions(final String serviceURL, final String queryStr) {

            this.serviceURL = serviceURL;
            
            this.queryStr = queryStr;

        }
        
	}

	private static void usage() {

		System.err.println("usage: (option)* [serviceURL] (query)");
		
	}

	/**
	 * Issue a query against a SPARQL endpoint. By default, the client will read
	 * from stdin. It will write on stdout.
	 * 
	 * @param args
	 *            <code>(option)* [serviceURL] (query)</code>
	 *            <p>
	 *            where
	 *            <dl>
	 *            <dt>serviceURL</dt>
	 *            <dd>The URL of the SPARQL endpoint.</dd>
	 *            <dt>query</dt>
	 *            <dd>The SPARQL query (required unless <code>-f</code> is used)
	 *            </dd>
	 *            <p>
	 *            where <i>option</i> is any of
	 *            <dl>
	 *            <dt>-u</dt>
	 *            <dd>username</dd>
	 *            <dt>-p</dt>
	 *            <dd>password</dd>
	 *            <dt>-timeout</dt>
	 *            <dd>The connection timeout in milliseconds (default
	 *            {@value #DEFAULT_TIMEOUT}).</dd>
	 *            <dt>-showQuery</dt>
	 *            <dd>Show the parser query (operator tree).</dd>
	 *            <dt>-hideResults</dt>
	 *            <dd>Hide the query results.</dd>
	 *            <dt>-verbose</dt>
	 *            <dd>Be verbose.</dd>
	 *            <dt>-quiet</dt>
	 *            <dd>Be quiet.</dd>
	 *            <dt>-f</dt>
	 *            <dd>A file (or directory) containing the query(s) to be run.
	 *            Each file may contain a single SPARQL query.</dd>
	 *            <dt>-query</dt>
	 *            <dd>The query follows immediately on the command line (be sure
	 *            to quote the query).</dd>
	 *            <dt>-repeat #</dt>
	 *            <dd>The #of times to present each query. A seed of ZERO (0)
	 *            will disable the randomized presentation of the queries. The
	 *            default seed is based on the System clock.</dd>
	 *            <dt>-seed seed</dt>
	 *            <dd>Randomize the presentation of the queries, optionally
	 *            using the specified seed for the random number generator.</dd>
	 *            <dt>-t</dt>
	 *            <dd>The http connection timeout in milliseconds -or- ZERO (0)
	 *            for an infinite timeout.</dd>
	 *            <dt>-defaultGraph</dt>
	 *            <dd>The URI of the default graph to use for the query.</dd>
	 *            <dt>-help</dt>
	 *            <dd>Display help.</dd>
	 *            <dt>--?</dt>
	 *            <dd>Display help.</dd>
	 *            </dl>
	 * @throws Exception
	 * 
	 * @todo username/password not supported.
	 */
	public static void main(final String[] args) throws Exception {

		if (args.length == 0) {
			usage();
			System.exit(1);
		}

		/*
         * Parse the command line, overriding various properties.
         */
		long seed = System.nanoTime(); // Note: 0L means not randomized.
		int repeat = 1; // repeat count.
		File file = null; // When non-null, file or directory containing query(s).
		String queryStr = null; // A query given directly on the command line.
		final QueryOptions opts = new QueryOptions();
        {

            int i = 0;
            for (; i < args.length && args[i].startsWith("-"); i++) {

                final String arg = args[i];

                if (arg.equals("-u")) {

                	opts.username = args[++i];
                    
                } else if (arg.equals("-p")) {

                	opts.password = args[++i];

                } else if (arg.equals("-f")) {

                    file = new File(args[++i]);
                    
//                    opts.queryStr = readFromFile(new File(file));

				} else if (arg.equals("-showQuery")) {

					opts.showQuery = true;

				} else if (arg.equals("-hideResults")) {

					opts.hideResults = true;

                } else if (arg.equals("-verbose")) {
                    
                    opts.verbose = true;
                    opts.quiet = false;

                } else if (arg.equals("-quiet")) {
                    
                    opts.verbose = false;
                    opts.quiet = true;
                    
                } else if (arg.equals("-query")) {

                    queryStr = args[++i];

				} else if (arg.equals("-repeat")) {

                    if ((repeat = Integer.valueOf(args[++i])) < 1) {

                        throw new IllegalArgumentException("Bad repeat.");
                        
				    }

				} else if (arg.equals("-seed")) {

                    seed = Long.valueOf(args[++i]);
                    
				} else if (arg.equals("-t")) {

                    if ((opts.timeout = Integer.valueOf(args[++i])) < 0) {

                        throw new IllegalArgumentException("Bad timeout.");
                        
				    }

                    if (opts.verbose)
                        System.err.println("timeout: "
                                + (opts.timeout == 0 ? "infinite" : (""
                                        + opts.timeout + "ms")));

				} else if (arg.equals("-defaultGraph")) {

					opts.defaultGraphUri = args[++i];

                    if (opts.verbose)
                        System.err.println("defaultGraph: "
                                + opts.defaultGraphUri);

				} else if (arg.equals("-help") || arg.equals("--?")) {

					usage();

					System.exit(1);

				} else {

					throw new UnsupportedOperationException("Unknown option: "
							+ arg);

				}

			} // next arg.

			// The next argument is the serviceURL, which is required.
			if (i < args.length) {
				opts.serviceURL = args[i++];
                if (opts.verbose)
                    System.err.println("serviceURL: " + opts.serviceURL);
			} else {
				usage();
				System.exit(1);
			}

        } // parse command line.

//		// create a singular HttpClient object
//		final HttpClient client = new HttpClient();
//
//		{
//
//			final HttpConnectionManagerParams params = client
//					.getHttpConnectionManager().getParams();
//			
//			// Set timeout until a connection is established (ms).
//			params.setConnectionTimeout(5000/* timeout(ms) */);
//			
//		}
//		
//		// Set default credentials.
//		if (username != null) {
//
//			final Credentials creds = new UsernamePasswordCredentials(username,
//					password);
//
//			client.getState().setCredentials(AuthScope.ANY, creds);
//
//		}

        final String[] queries; // The query(s) to run.
        final String[] sources; // The source for each query (stdin|filename).
		if (file != null) {

			/*
			 * Read the query(s) from the file system.
			 */
			
			if (opts.verbose)
				System.err.println("Reading query(s) from file: " + file);
			
			// Figure out which files will be read.
			final List<File> fileList = new LinkedList<File>();

			if (!opts.quiet)
				System.err.println("Read " + fileList.size()
						+ " queries from: " + file);
			
			getFiles(file, fileList);

			// Read the query(s) from the file or directory.
			queries = readQueries(fileList);

			// Array of source filenames.
			sources = new String[fileList.size()];
			
			int i = 0;
			
			for (File t : fileList) {

				sources[i++] = t.toString();
				
			}

		} else {

			/*
			 * Run a single query. Either the query was given as a command line
			 * argument or we will read it from stdin now.
			 */
			
			if (queryStr == null) {

				if (opts.verbose)
					System.err.println("Reading query from stdin...");

				queryStr = readFromStdin();
				
				sources = new String[] { "stdin" };

			} else {

				sources = new String[] { "command line" };

			}

			// An array with just the one query.
			queries = new String[] { queryStr };
			
		}

		/*
		 * The order in which those queries will be executed. The elements of
		 * this array are indices into the query[]. The length of the array is
		 * the #of queries given times the repeat count.
		 */
		final int[] order;
		{
			// Total #of trials to execute.
			final int ntrials = queries.length * repeat;

			// Determine the query presentation order.
			if (seed == 0) {
				// Run queries in the given order.
				order = new int[ntrials];
				for (int i = 0; i < ntrials; i++) {
					order[i] = i;
				}
			} else {
				// Run queries in a randomized order.
				order = getRandomOrder(seed, ntrials);
			}
			// Now normalize the query index into [0:nqueries).
			for (int i = 0; i < ntrials; i++) {
				order[i] = order[i] % queries.length;
			}
		}

		/*
		 * Run trials.
		 * 
		 * @todo option for concurrent clients (must clone opts).
		 */

		//		System.err.println("order="+Arrays.toString(order));
		
		// cumulative elapsed nanos for each presentation of each query
		final long[] elapsedTimes = new long[queries.length];
		
		for (int i = 0; i < order.length; i++) {

			final int queryId = order[i];
			
			opts.queryStr = queries[queryId];
			
			final String source = sources[queryId];

			// Run the query
			final long elapsed;
			{
				final long begin = System.nanoTime();

				/*
				 * @todo modify to count results and return that rather than the
				 * elapsed time, which we can figure out easily enough for
				 * ourselves.
				 */
				new Query(/* client, */opts).call();

				elapsed = System.nanoTime() - begin;

				elapsedTimes[queryId] += elapsed;

			}

			if (!opts.quiet) {
				// Show the query run time.
				System.err.println("queryId=" + queryId + ", source=" + source
						+ ", elapsed=" + TimeUnit.NANOSECONDS.toMillis(elapsed)
						+ "ms");
			}
			
		}

		if (repeat > 1) {
			
			/*
			 * Report the average running time for each query.
			 * 
			 * @todo track the #of results and verify that all runs of each
			 * query return the same #of results.
			 */
			
			System.out.println("source, average(ms)");

			for (int i = 0; i < queries.length; i++) {

				final long elapsedNanos = elapsedTimes[i] / repeat;

				System.out.println(TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
						+ ", " + sources[i]);

			}

		}

		// Normal exit.
		System.exit(0);

	}

}
