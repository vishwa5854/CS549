/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.stevens.cs549.ftpclient;

import edu.stevens.cs549.ftpinterface.IServer;
import edu.stevens.cs549.ftpinterface.IServerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 
 * @author dduggan
 */
public class Client {

	enum Mode {
		NONE, PASSIVE, ACTIVE
	};

	private static int BACKLOG_LENGTH = 5;

	private static String clientPropsFile = "/client.properties";

	protected String clientIp;

	protected String serverAddr;

	protected int serverPort;

	private static Logger log = Logger.getLogger(Client.class.getCanonicalName());

	public void severe(String s) {
		log.severe(s);
	}

	public void warning(String s) {
		log.info(s);
	}

	public void info(String s) {
		log.info(s);
	}

	protected List<String> processArgs(String[] args) {
		List<String> commandLineArgs = new ArrayList<String>();
		int ix = 0;
		Hashtable<String, String> opts = new Hashtable<String, String>();

		while (ix < args.length) {
			if (args[ix].startsWith("--")) {
				String option = args[ix++].substring(2);
				if (ix == args.length || args[ix].startsWith("--"))
					severe("Missing argument for --" + option + " option.");
				else if (opts.containsKey(option))
					severe("Option \"" + option + "\" already set.");
				else
					opts.put(option, args[ix++]);
			} else {
				commandLineArgs.add(args[ix++]);
			}
		}
		/*
		 * Overrides of values from configuration file.
		 */
		Enumeration<String> keys = opts.keys();
		while (keys.hasMoreElements()) {
			String k = keys.nextElement();
			if ("clientIp".equals(k))
				clientIp = opts.get("clientIp");
			else if ("serverAddr".equals(k))
				serverAddr = opts.get("serverAddr");
			else if ("serverPort".equals(k))
				serverPort = Integer.parseInt(opts.get("serverPort"));
			else
				severe("Unrecognized option: --" + k);
		}

		return commandLineArgs;
	}

	/**
	 * @param args the command line arguments
	 */
	public static void main(String[] args) {
		new Client(args);
	}

	public Client(String[] args) {
		try {
			/*
			 * Load client properties.
			 */
			Properties props = new Properties();
			InputStream in = getClass().getResourceAsStream(clientPropsFile);
			props.load(in);
			in.close();
			clientIp = (String) props.get("client.ip");
			serverAddr = (String) props.get("server.ip");
			String serverName = (String) props.get("server.name");
			serverPort = Integer.parseInt((String) props.get("server.port"));

			/*
			 * Overrides from command-line
			 */
			processArgs(args);

			log.info("Client IP = " + clientIp);
			log.info("Server addr = " + serverAddr);
			log.info("Server port = " + serverPort);
			log.info("Server name = " + serverName);

			/*
			 * TODO: Get a server proxy.
			 */
			Registry registry = LocateRegistry.getRegistry(serverAddr, serverPort);
			IServerFactory iServerFactory = (IServerFactory) registry.lookup(serverName);
			IServer server = iServerFactory.createServer();


			/*
			 * Start CLI. Second argument should be server proxy.
			 */
			cli(serverAddr, server);

		} catch (java.io.FileNotFoundException e) {
			log.severe("Client error: " + clientPropsFile + " file not found.");
		} catch (java.io.IOException e) {
			log.severe("Client error: IO exception.");
			e.printStackTrace();
		} catch (Exception e) {
			log.severe("Client exception:");
			e.printStackTrace();
		}

	}

	static void msg(String m) {
		System.out.print(m);
	}

	static void msgln(String m) {
		System.out.println(m);
	}

	static void err(Exception e) {
		System.err.println("Error : " + e);
		e.printStackTrace();
	}

	public void cli(String svrHost, IServer svr) {

		// Main command-line interface loop

		try {
			InetAddress serverAddress = InetAddress.getByName(svrHost);
			Dispatch d = new Dispatch(svr, serverAddress);
			BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

			while (true) {
				msg("ftp> ");
				String line = in.readLine();
				String[] inputs = line.split("\\s+");
				if (inputs.length > 0) {
					String cmd = inputs[0];
					if (cmd.length() == 0)
						;
					else if ("get".equals(cmd))
						d.get(inputs);
					else if ("put".equals(cmd))
						d.put(inputs);
					else if ("cd".equals(cmd))
						d.cd(inputs);
					else if ("pwd".equals(cmd))
						d.pwd(inputs);
					else if ("dir".equals(cmd))
						d.dir(inputs);
					else if ("ldir".equals(cmd))
						d.ldir(inputs);
					else if ("port".equals(cmd))
						d.port(inputs);
					else if ("pasv".equals(cmd))
						d.pasv(inputs);
					else if ("help".equals(cmd))
						d.help(inputs);
					else if ("quit".equals(cmd))
						return;
					else
						msgln("Bad input.  Type \"help\" for more information.");
				}
			}
		} catch (EOFException ignored) {
		} catch (IOException e) {
			err(e);
			System.exit(-1);
		}
	}

	public class Dispatch {

		private IServer svr;

		private InetAddress serverAddress;

		Dispatch(IServer s, InetAddress sa) {
			svr = s;
			serverAddress = sa;
		}

		public void help(String[] inputs) {
			if (inputs.length == 1) {
				msgln("Commands are:");
				msgln("  get filename: download file from server");
				msgln("  put filename: upload file to server");
				msgln("  pwd: current working directory on server");
				msgln("  cd filename: change working directory on server");
				msgln("  dir: list contents of working directory on server");
				msgln("  ldir: list contents of current directory on client");
				msgln("  port: server should transfer files in active mode");
				msgln("  pasv: server should transfer files in passive mode");
				msgln("  quit: exit the client");
			}
		}

		/*
		 * *****************************************************************************
		 * *************** Data connection.
		 */

		/*
		 * Note: This refers to the mode of the SERVER.
		 */
		private Mode mode = Mode.NONE;

		/*
		 * If active mode, remember the client socket.
		 */

		private ServerSocket dataChan = null;

		private int makeActive() throws IOException {
			InetAddress myAddr = InetAddress.getByName(clientIp);
			log.info("Client binding to server socket at address " + myAddr);
			dataChan = new ServerSocket(0, BACKLOG_LENGTH, myAddr);
			mode = Mode.ACTIVE;
			/*
			 * Note: this only works (for the server) if the client is not behind a NAT.
			 */

			return dataChan.getLocalPort();
		}

		/*
		 * If passive mode, remember the server socket address.
		 */
		private InetSocketAddress serverSocket = null;

		private void makePassive(int serverPort) {
			serverSocket = InetSocketAddress.createUnresolved(serverAddr, serverPort);
			mode = Mode.PASSIVE;
		}

		/*
		 * *****************************************************************************
		 * The client needs to create threads if running in active mode.
		 */

		private class GetThread implements Runnable {
			/*
			 * This client-side thread runs when the server is active mode and a file
			 * download is initiated. This thread listens for a connection request from the
			 * server. The client-side server socket (...) should have been created when the
			 * port command put the server in active mode.
			 */
			private ServerSocket dataChan = null;
			private OutputStream out = null;

			public GetThread(ServerSocket s, OutputStream o) {
				dataChan = s;
				out = o;
			}

			public void run() {
				try {
					log.info("Client accepting conn request at port " + dataChan.getLocalPort());
					log.info("before dataChan.accept()");
					Socket socket = dataChan.accept();
					DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());

					try {
						log.info("Received connection request from server on client");
						/*
						 * TODO: Complete this thread.
						 */

						byte[] data;

						// Reading 4K blocks at a time.
						while (true) {
							data = dataInputStream.readNBytes(4096);

							if (data.length == 0) {
								break;
							}
							out.write(data);
						}
					} finally {
						out.flush();
						out.close();
						dataInputStream.close();
						log.info("Closed data input stream");
						socket.close();
					}
				} catch (IOException e) {
					msg("Exception: " + e);
					e.printStackTrace();
				}
			}
		}

		private class PutThread implements Runnable {
			/*
			 * This client-side thread runs when the server is active mode and a file
			 * download is initiated. This thread listens for a connection request from the
			 * server. The client-side server socket (...) should have been created when the
			 * port command put the server in active mode.
			 */
			private ServerSocket dataChan = null;
			private InputStream in = null;

			public PutThread(ServerSocket s, InputStream i) {
				dataChan = s;
				in = i;
			}

			public void run() {
				try {
					log.info("Client accepting conn request at port " + dataChan.getLocalPort());
					Socket socket = dataChan.accept();
					OutputStream outputStream = socket.getOutputStream();

					try {
						log.info("Received connection request from server on client");
						/*
						 * TODO: Complete this thread.
						 */
						byte[] data;

						while (true) {
							data = in.readNBytes(4096);

							if (data.length == 0) {
								break;
							}
							outputStream.write(data);
						}
					} finally {
						outputStream.flush();
						outputStream.close();
						in.close();
						socket.close();
					}
				} catch (IOException e) {
					msg("Exception: " + e);
					e.printStackTrace();
				}
			}
		}

		/**
		 * Kashi Vishwanath Bondugula
		 * CWID: 20012715
		 * CS549 Assignment 1 Demo
		 * */
		
		public void get(String[] inputs) {
			if (inputs.length == 2) {
				try {
					if (mode == Mode.PASSIVE) {
						/*
						 * Get server ready for transfer.
						 */
						svr.get(inputs[1]);
						/*
						 * Open the output local file and connect to the server socket.
						 */
						OutputStream out = new BufferedOutputStream(new FileOutputStream(inputs[1]));
						log.info("Client connecting to server at address " + serverAddress);
						Socket socket = new Socket(serverAddress, serverSocket.getPort());
						try {
							/*
							 * TODO: download the file through the socket connection
							 */

							DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());
							byte[] data;

							// Reading 4K blocks at a time.
							while (true) {
								data = dataInputStream.readNBytes(4096);

								if (data.length == 0) {
									break;
								}
								out.write(data);
							}
							dataInputStream.close();
							/*
							 * End TODO
							 */
							
						} finally {
							out.flush();
							out.close();
							socket.close();
						}

					} else if (mode == Mode.ACTIVE) {
						/*
						 * Open the output local file and get the client ready for the transfer.
						 */
						OutputStream out = new BufferedOutputStream(new FileOutputStream(inputs[1]));
						log.info("calling get thread in active mode");
						new Thread(new GetThread(dataChan, out)).start();
						/*
						 * Start the transfer on the server.
						 */
						log.info("Calling svr.get");
						svr.get(inputs[1]);
					} else {
						msgln("GET: No mode set--use port or pasv command.");
					}
				} catch (Exception e) {
					err(e);
				}
			}
		}

		public void put(String[] inputs) {
			if (inputs.length == 2) {
				try {
					if (mode == Mode.PASSIVE) {
						/*
						 * TODO
						 */
						svr.put(inputs[1]);
						InputStream in = new BufferedInputStream(new FileInputStream(inputs[1]));
						log.info("Client connecting to server at address " + serverAddress);
						Socket socket = new Socket(serverAddress, serverSocket.getPort());
						OutputStream out = socket.getOutputStream();

						try {
							byte[] data;

							while (true) {
								data = in.readNBytes(4096);

								if (data.length == 0) {
									break;
								}
								out.write(data);
							}
						} finally {
							out.flush();
							out.close();
							socket.close();
						}
					} else if (mode == Mode.ACTIVE) {
						/*
						 * TODO: Need to create a thread
						 */
						InputStream in = new BufferedInputStream(new FileInputStream(inputs[1]));
						new Thread(new PutThread(dataChan, in)).start();
						svr.put(inputs[1]);
					} else {
						msgln("GET: No mode set--use port or pasv command.");
					}
				} catch (Exception e) {
					err(e);
				}
			}
		}

		public void cd(String[] inputs) {
			if (inputs.length == 2)
				try {
					svr.cd(inputs[1]);
					msgln("CWD: " + svr.pwd());
				} catch (Exception e) {
					err(e);
				}
		}

		public void pwd(String[] inputs) {
			if (inputs.length == 1)
				try {
					msgln("CWD: " + svr.pwd());
				} catch (Exception e) {
					err(e);
				}
		}

		public void dir(String[] inputs) {
			if (inputs.length == 1) {
				try {
					String[] fs = svr.dir();
					for (int i = 0; i < fs.length; i++) {
						msgln(fs[i]);
					}
				} catch (Exception e) {
					err(e);
				}
			}
		}

		public void pasv(String[] inputs) {
			if (inputs.length == 1) {
				try {
					makePassive(svr.pasv());
					msgln("PASV: Server in passive mode.");
				} catch (Exception e) {
					err(e);
				}
			}
		}

		public void port(String[] inputs) {
			if (inputs.length == 1) {
				try {
					int s = makeActive();
					svr.port(s);
					msgln("PORT: Server in active mode.");
				} catch (Exception e) {
					err(e);
				}
			}
		}

		public void ldir(String[] inputs) {
			if (inputs.length == 1) {
				String[] fs = new File(".").list();
				for (int i = 0; i < fs.length; i++) {
					msgln(fs[i]);
				}
			}
		}

	}

}
