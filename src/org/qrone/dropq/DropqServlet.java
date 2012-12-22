package org.qrone.dropq;

import java.io.IOException;
import javax.servlet.http.*;

@SuppressWarnings("serial")
public class DropqServlet extends HttpServlet {
	public void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		new DropqServletInstance().doPost(req, resp);
	}
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		new DropqServletInstance().doGet(req, resp);
	}
}
