package com.thedeanda.ajaxproxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

public class APFilter implements Filter {
	private static final Logger log = Logger.getLogger(APFilter.class);
	private int maxBitrate = 0; // in KBps
	private int forcedLatency = 50;
	private boolean logRequests = false;
	private String appendToPath = "";
	private Semaphore throttleLock;
	private List<AccessTracker> trackers = new ArrayList<AccessTracker>();
	private Thread trackerThread;
	private List<LoadedResource> trackBuffer = new LinkedList<LoadedResource>();

	@Override
	public void destroy() {
		if (trackerThread != null) {
			trackerThread.interrupt();
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		if (request instanceof HttpServletRequest) {
			doFilterInternal(request, response, chain);
		} else {
			chain.doFilter(request, response);
		}
	}

	public void doFilterInternal(ServletRequest request,
			ServletResponse response, FilterChain chain) throws IOException,
			ServletException {
		LoadedResource resource = new LoadedResource();
		long start = System.currentTimeMillis();

		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;
		MyServletRequestWrapper reqWrapper = new MyServletRequestWrapper(
				httpRequest);
		MyServletResponseWrapper wrapper = new MyServletResponseWrapper(
				httpResponse);
		String url = httpRequest.getRequestURI();
		String qs = httpRequest.getQueryString();
		if (null != qs && !"".equals(qs))
			url += "?" + qs;
		resource.setUrl(url);

		String method = httpRequest.getMethod();
		resource.setMethod(method);

		// read input
		if (("POST".equals(method) || "PUT".equals(method))) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(reqWrapper.getClonedInputStream(), baos);
			resource.setInput(baos.toByteArray());
		}

		Enumeration<?> headerNames = reqWrapper.getHeaderNames();
		while (headerNames.hasMoreElements()) {
			String name = (String) headerNames.nextElement();
			resource.addHeader(name, reqWrapper.getHeader(name));
		}

		if (forcedLatency > 0) {
			try {
				Thread.sleep(this.forcedLatency);
			} catch (InterruptedException e) {
				// we probably don't need to continue processing
				return;
			}
		}
		chain.doFilter(reqWrapper, wrapper);
		throttledCopy(wrapper.getNewInputStream(), response.getOutputStream());

		resource.setStatusCode(wrapper.getStatus());

		// read cookies
		Cookie[] cookies = ((HttpServletRequest) request).getCookies();
		if (cookies != null && cookies.length > 0) {
			ArrayList<Cookie> cs = new ArrayList<Cookie>();
			for (Cookie c : cookies) {
				cs.add(c);
			}
			resource.setCookies(new ArrayList<Cookie>());
		}

		// read output
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		IOUtils.copy(wrapper.getNewInputStream(), baos);
		resource.setOutput(baos.toByteArray());

		resource.setDuration(System.currentTimeMillis() - start);
		trackAccess(resource);
	}

	@Override
	public void init(FilterConfig config) throws ServletException {
		throttleLock = new Semaphore(1);
		trackerThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while (!Thread.interrupted()) {
						List<LoadedResource> localBuffer = new ArrayList<LoadedResource>();
						synchronized (trackBuffer) {
							if (trackBuffer.isEmpty())
								trackBuffer.wait();
							while (!trackBuffer.isEmpty())
								localBuffer.add(trackBuffer.remove(0));
						}

						for (LoadedResource ti : localBuffer) {
							for (AccessTracker at : trackers) {
								try {
									at.trackFile(ti);
								} catch (Exception e) {
									log.error(e.getMessage(), e);
								}
							}
						}
					}
				} catch (InterruptedException e) {

				}
			}
		});
		trackerThread.start();
	}

	public int getMaxBitrate() {
		return maxBitrate;
	}

	public void setMaxBitrate(int maxBitrate) {
		this.maxBitrate = maxBitrate;
	}

	public int getForcedLatency() {
		return forcedLatency;
	}

	public void setForcedLatency(int forcedLatency) {
		log.trace("setting forced latency: " + forcedLatency);
		this.forcedLatency = forcedLatency;
	}

	private void throttledCopy(ByteArrayInputStream is, OutputStream os) {
		boolean release = false;
		try {
			if (this.maxBitrate > 0) {
				throttleLock.acquire();
				release = true;

				int blocks = 10;
				int bytesPerBlock = maxBitrate * 1024 / blocks;
				byte[] buffer = new byte[bytesPerBlock];
				int delay = 1000 / blocks;
				int read = 0;

				do {
					read = is.read(buffer);
					if (read > 0)
						os.write(buffer, 0, read);
					Thread.sleep(delay);
				} while (read > 0);

				// TODO: actually throttle it
				// IOUtils.copy(is, os);
			} else {
				IOUtils.copy(is, os);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			if (release)
				throttleLock.release();
			try {
				is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private class MyServletRequestWrapper extends HttpServletRequestWrapper {

		private byte[] data;

		public MyServletRequestWrapper(HttpServletRequest request) {
			super(request);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try {
				IOUtils.copy(request.getInputStream(), baos);
			} catch (IOException e) {
			} finally {
				try {
					baos.close();
				} catch (IOException e) {
				}
			}

			this.data = baos.toByteArray();
		}

		@Override
		public ServletInputStream getInputStream() {
			return new MyServletInputStream(getClonedInputStream());
		}

		public ByteArrayInputStream getClonedInputStream() {
			return new ByteArrayInputStream(data);
		}
	}

	private class MyServletInputStream extends ServletInputStream {
		private InputStream is;

		public MyServletInputStream(InputStream is) {
			this.is = is;
		}

		@Override
		public int read() throws IOException {
			return is.read();
		}
	}

	private class MyServletResponseWrapper extends HttpServletResponseWrapper {
		private ByteArrayOutputStream baos;
		private PrintWriter writer;
		private MyServletOutputStream os;
		private int httpStatus = 200;

		public MyServletResponseWrapper(HttpServletResponse response) {
			super(response);

			baos = new ByteArrayOutputStream();
			writer = new PrintWriter(baos);
			os = new MyServletOutputStream(baos);
		}

		@Override
		public void flushBuffer() {

		}

		@Override
		public ServletOutputStream getOutputStream() {
			return os;
		}

		@Override
		public PrintWriter getWriter() {
			return writer;
		}

		public ByteArrayInputStream getNewInputStream() {
			return new ByteArrayInputStream(baos.toByteArray());
		}

		@Override
		public void reset() {
			super.reset();
			this.httpStatus = SC_OK;
		}

		@Override
		public void sendError(int sc) throws IOException {
			httpStatus = sc;
			super.sendError(sc);
		}

		@Override
		public void sendRedirect(String location) throws IOException {
			httpStatus = 302;
			super.sendRedirect(location);
		}

		@Override
		public void sendError(int sc, String msg) throws IOException {
			httpStatus = sc;
			super.sendError(sc, msg);
		}

		@Override
		public void setStatus(int sc) {
			httpStatus = sc;
			super.setStatus(sc);
		}

		public int getStatus() {
			return httpStatus;
		}
	}

	private class MyServletOutputStream extends ServletOutputStream {
		private OutputStream os;

		public MyServletOutputStream(OutputStream os) {
			this.os = os;
		}

		@Override
		public void write(int bite) throws IOException {
			os.write(bite);
		}
	}

	public boolean isLogRequests() {
		return logRequests;
	}

	public String getAppendToPath() {
		return appendToPath;
	}

	public void setLogRequests(boolean logRequests) {
		this.logRequests = logRequests;
	}

	public void setAppendToPath(String appendToPath) {
		this.appendToPath = appendToPath;
	}

	public void add(AccessTracker tracker) {
		trackers.add(tracker);
	}

	private void trackAccess(LoadedResource resource) {
		synchronized (trackBuffer) {
			trackBuffer.add(resource);
			trackBuffer.notify();
		}
	}

}
