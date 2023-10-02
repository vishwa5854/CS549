package edu.stevens.cs549.ftpserver;

import edu.stevens.cs549.ftpinterface.IServer;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.RemoteException;
import java.rmi.server.ServerNotActiveException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Enumeration;
import java.util.Stack;
import java.util.logging.Logger;

/**
 *
 * @author dduggan
 */
public class Server extends UnicastRemoteObject implements IServer {

	static final long serialVersionUID = 0L;

	public static Logger log = Logger.getLogger("edu.stevens.cs.cs549.ftpserver");

	/*
	 * For multi-homed hosts, must specify IP address on which to bind a server
	 * socket for file transfers. See the constructor for ServerSocket that allows
	 * an explicit IP address as one of its arguments.
	 */
	private InetAddress host;

	final static int BACKLOG_LENGTH = 5;

	/*
	 *********************************************************************************************
	 * Current working directory.
	 */
	static final int MAX_PATH_LEN = 1024;
	private Stack<String> cwd = new Stack<String>();

	/*
	 *********************************************************************************************
	 * Data connection.
	 */

	enum Mode {
		NONE, PASSIVE, ACTIVE
	};

	private Mode mode = Mode.NONE;

	/*
	 * If passive mode, remember the server socket.
	 */

	private ServerSocket dataChan = null;

	private int makePassive() throws IOException {
		dataChan = new ServerSocket(0, BACKLOG_LENGTH, host);
		mode = Mode.PASSIVE;
//    	return (InetSocketAddress)(dataChan.getLocalSocketAddress());
		return dataChan.getLocalPort();
	}

	/*
	 * If active mode, remember the client socket address.
	 */
	private InetSocketAddress clientSocket = null;

	private void makeActive(int clientPort) {
//    	clientSocket = s;
		try {
			clientSocket = InetSocketAddress.createUnresolved(getClientHost(), clientPort);
		} catch (ServerNotActiveException e) {
			throw new IllegalStateException("Make active", e);
		}
		mode = Mode.ACTIVE;
	}

	/*
	 **********************************************************************************************
	 */

	/*
	 * The server can be initialized to only provide subdirectories of a directory
	 * specified at start-up.
	 */
	private final String pathPrefix;

	public Server(InetAddress host, int port, String prefix) throws RemoteException {
		super(port);
		this.host = host;
		this.pathPrefix = prefix + "/";
		log.info("A client has bound to a server instance.");
	}

	public Server(InetAddress host, int port) throws RemoteException {
		this(host, port, "/");
	}

	private boolean valid(String s) {
		// File names should not contain "/".
		return (s.indexOf('/') < 0);
	}
	
	/*
	 * *****************************************************************************
	 * The server needs to create threads if running in passive mode.
	 */

	private static class GetThread implements Runnable {
		private ServerSocket dataChan = null;
		private InputStream in = null;

		public GetThread(ServerSocket s, InputStream i) {
			dataChan = s;
			in = i;
		}

		public void run() {
			try {
				Socket socket = dataChan.accept();
				OutputStream out = socket.getOutputStream();

				try {
					log.info("Received connection request from client on server");
					/*
					 * TODO: Complete this thread (remember to flush output!).
					 */
					byte[] data;

					while (true) {
						data = in.readNBytes(4096);

						if (data.length == 0) {
							break;
						}
						out.write(data);
					}
					
					/*
					 * End TODO
					 */
				} finally {
					out.flush();
					out.close();
					socket.close();
					in.close();
				}
			} catch (IOException e) {
				throw new IllegalStateException("Exception while transferring data to client in passive mode.", e);
			}
		}
	}

	private static class PutThread implements Runnable {
		private ServerSocket dataChan = null;
		private OutputStream out = null;

		public PutThread(ServerSocket s, OutputStream f) {
			dataChan = s;
			out = f;
		}

		public void run() {
			try {
				Socket socket = dataChan.accept();
				InputStream in = socket.getInputStream();
				try {
					log.info("Received connection request from client on server");
					/*
					 * TODO: Complete this thread.
					 */
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
					in.close();
					socket.close();
				}
			} catch (IOException e) {
				throw new IllegalStateException("Exception while transferring data from client in passive mode.", e);
			}
		}
	}
	
	public void get(String file) throws IOException, FileNotFoundException, RemoteException {
		if (!valid(file)) {
			throw new IOException("Bad file name: " + file);
		} else if (mode == Mode.ACTIVE) {
			/*
			 * Open the local input file and connect to the client socket to start downloading.
			 */
			InputStream in = new BufferedInputStream(new FileInputStream(path() + file));
			log.info("Server connecting to client at address " + clientSocket.getHostName() + " and port "+clientSocket.getPort());
			Socket socket = new Socket(clientSocket.getHostName(), clientSocket.getPort());
			OutputStream out = socket.getOutputStream();

			try {
				/*
				 * TODO: connect to client socket to transfer file.
				 */
				byte[] data;

				while (true) {
					data = in.readNBytes(4096);

					if (data.length == 0) {
						break;
					}
					out.write(data);
				}
				/*
				 * End TODO.
				 */
			} finally {
				out.flush();
				out.close();
				in.close();
				socket.close();
			}
		} else if (mode == Mode.PASSIVE) {
			InputStream in = new BufferedInputStream(new FileInputStream(path() + file));
			new Thread(new GetThread(dataChan, in)).start();
		}
	}

	public void put(String file) throws IOException, FileNotFoundException, RemoteException {
		if (!valid(file)) {
			throw new IOException("Bad file name: " + file);
		} else if (mode == Mode.ACTIVE) {
			/*
			 * TODO
			 */
			OutputStream out = new BufferedOutputStream(new FileOutputStream(path() + file));
			log.info("Server connecting to client at address " + clientSocket.getHostName() + " and port "+clientSocket.getPort());
			Socket socket = new Socket(clientSocket.getHostName(), clientSocket.getPort());
			InputStream in = socket.getInputStream();

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
				in.close();
				socket.close();
			}
		} else if (mode == Mode.PASSIVE) {
			/*
			 * TODO
			 */
			OutputStream out = new BufferedOutputStream(new FileOutputStream(path() + file));
			new Thread(new PutThread(dataChan, out)).start();
		}
	}

	public String[] dir() throws RemoteException {
		// List the contents of the current directory.
		return new File(path()).list();
	}

	public void cd(String dir) throws IOException, RemoteException {
		// Change current working directory (".." is parent directory)
		if (!valid(dir)) {
			throw new IOException("Bad file name: " + dir);
		} else {
			if ("..".equals(dir)) {
				if (cwd.size() > 0)
					cwd.pop();
				else
					throw new IOException("Already in root directory!");
			} else if (".".equals(dir)) {
				;
			} else {
				File f = new File(path() + "/" + dir);
				if (!f.exists())
					throw new IOException("Directory does not exist: " + dir);
				else if (!f.isDirectory())
					throw new IOException("Not a directory: " + dir);
				else
					cwd.push(dir);
			}
		}
	}

	public String pwd() throws RemoteException {
		// List the current working directory.
		String p = "/";
		for (Enumeration<String> e = cwd.elements(); e.hasMoreElements();) {
			p = p + e.nextElement() + "/";
		}
		return p;
	}

	private String path() throws RemoteException {
		return pathPrefix + pwd();
	}

	public void port(int clientPort) {
		makeActive(clientPort);
	}

	public int pasv() throws IOException {
		return makePassive();
	}

}
