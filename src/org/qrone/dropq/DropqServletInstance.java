package org.qrone.dropq;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.*;

import net.arnx.jsonic.JSON;
import net.arnx.jsonic.util.Base64;

import com.google.appengine.api.datastore.Blob;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;

public class DropqServletInstance {
	private static MemcacheService mem = 
			MemcacheServiceFactory.getMemcacheService("org.qrone.dropq");
	private static URLFetchService curl = URLFetchServiceFactory.getURLFetchService();
	private static DatastoreService ds = DatastoreServiceFactory.getDatastoreService();
	
	private String path;
	private String cpath;
	public void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		path = req.getPathInfo();
		
		// github hook
		if(path.equals("/system/hook/github")){
			Map map = (Map)JSON.decode(req.getParameter("payload"));
			List ary = (List)map.get("commits");
			mem.delete("map");
			
			for (Iterator iter = ary.iterator(); iter.hasNext();) {
				Map item = (Map)iter.next();
				
				cacheclear((List)item.get("removed"));
				cacheclear((List)item.get("modified"));
				
			}
			
			resp.setStatus(200);
		}
	}
	
	public void cacheclear(List ary){
		for (Iterator iter = ary.iterator(); iter.hasNext();) {
			String githubpath = (String)iter.next();
			
			mem.delete("blobs./" + githubpath);
			ds.delete(KeyFactory.createKey("blobs", "/" + githubpath));
		}
	}
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		path = req.getPathInfo();
		cpath = calcPath(path);
		
		// set content-type
		if(cpath.endsWith(".png")){
			resp.setContentType("image/png");
		}else if(cpath.endsWith(".gif")){
			resp.setContentType("image/gif");
		}else if(cpath.endsWith(".jpeg")){
			resp.setContentType("image/jpeg");
		}else if(cpath.endsWith(".jpg")){
			resp.setContentType("image/jpeg");
		}else if(cpath.endsWith(".js")){
			resp.setContentType("text/javascript");
		}else if(cpath.endsWith(".css")){
			resp.setContentType("text/css");
		}else if(cpath.endsWith(".txt")){
			resp.setContentType("text/plain");
		}else if(cpath.endsWith(".swf")){
			resp.setContentType("application/x-shockwave-flash");
		}else{
			resp.setContentType("text/html; charset=utf8");
		}
		
		// output data.
		try{
			resp.getOutputStream().write(getData());
		}catch(IOException e){
			resp.setStatus(404);
		}
	}

	public byte[] getData() throws IOException{
		
		// try memcached.
		try{
			Blob o = (Blob)mem.get("blobs." + cpath);
			if(o != null){
				return o.getBytes();
			}
		} catch (ClassCastException e) {
		}

		// try datastore.
		try {
			Entity en = ds.get(KeyFactory.createKey("blobs", cpath));
			Blob bytes = (Blob) en.getProperty("content");
			if(bytes.getBytes().length < 10 * 1024){
				mem.put("blobs." + cpath, bytes);
			}
			return bytes.getBytes();
		} catch (EntityNotFoundException e1) {
		} catch (ClassCastException e) {
		}
		
		// try github api.
		try {
			String sha = (String)getShaMap().get(cpath);
			HTTPResponse res = curl.fetch(
					new URL("https://api.github.com/repos/qronon/qrone-dry/git/blobs/" + sha));
			
			byte[] bytes = res.getContent();
			
			Map map = (Map)JSON.decode(new String(bytes, "utf8"));
			
			String encoding = (String)map.get("encoding");

			String str = (String)map.get("content");
			if(encoding != null && encoding.equals("base64")){
				bytes = Base64.decode(str);
			}else{
				bytes = str.getBytes();
			}
			
			if(bytes.length < 10 * 1024){
				mem.put("blobs." + cpath, new Blob(bytes));
			}
			
			Entity entity = new Entity(KeyFactory.createKey("blobs", cpath));
			entity.setProperty("content", new Blob(bytes));
			ds.put(entity);
			
			return bytes;
		
		} catch (MalformedURLException e) {
		} catch (IOException e) {
		}
		
		throw new IOException();
	}

	public String calcPath(String path) throws IOException{
		System.out.println(path);
		if(getShaMap().containsKey(path)){
			return path;
		}
		
		if(getShaMap().containsKey(path + ".html")){
			return path + ".html";
		}
		
		if(path.endsWith("/")){
			if(getShaMap().containsKey(path + "index.html")){
				return path + "index.html";
			}
		}

		if(path.equals("")){
			if(getShaMap().containsKey("index.html")){
				return "index.html";
			}
		}

		throw new IOException();
	}

	private Map shamap = null;
	public Map getShaMap() throws IOException{
		if(shamap != null){
			return shamap;
		}
		shamap = getShaMapImpl();
		System.out.println(shamap.toString());
		return shamap;
	}
	
	public Map getShaMapImpl() throws IOException{

		// try memcached.
		Object o = mem.get("map");
		if(o != null){
			System.out.println("memcache shamap");
			return (Map)JSON.decode(o.toString());
		}
		
		// try github api.
		try {
			HTTPResponse res = curl.fetch(
					new URL("https://api.github.com/repos/qronon/qrone-dry/git/trees/master?recursive=1"));
			byte[] bytes = res.getContent();
			
			Map obj = (Map)JSON.decode(new String(bytes, "utf8"));
			List a = (List)obj.get("tree");
			
			shamap = new HashMap();
			for (Iterator iter = a.iterator(); iter.hasNext();) {
				Map rec = (Map)iter.next();
				shamap.put("/" + rec.get("path").toString(), rec.get("sha").toString());
			}
			
			mem.put("map", JSON.encode(shamap));
			return shamap;
		
		} catch (MalformedURLException e) {
		} catch (IOException e) {
		} catch (NullPointerException e) {
		}
		
		throw new IOException();
	}
}
