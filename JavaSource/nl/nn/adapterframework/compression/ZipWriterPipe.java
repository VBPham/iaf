/*
 * $Log: ZipWriterPipe.java,v $
 * Revision 1.1  2010-01-06 17:57:35  L190409
 * classes for reading and writing zip archives
 *
 */
package nl.nn.adapterframework.compression;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.http.HttpServletResponse;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.ParameterException;
import nl.nn.adapterframework.core.PipeLine;
import nl.nn.adapterframework.core.PipeLineSession;
import nl.nn.adapterframework.core.PipeRunException;
import nl.nn.adapterframework.core.PipeRunResult;
import nl.nn.adapterframework.parameters.Parameter;
import nl.nn.adapterframework.parameters.ParameterResolutionContext;
import nl.nn.adapterframework.parameters.ParameterValue;
import nl.nn.adapterframework.parameters.ParameterValueList;
import nl.nn.adapterframework.pipes.FixedForwardPipe;
import nl.nn.adapterframework.util.ClassUtils;
import nl.nn.adapterframework.util.StreamUtil;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.lf5.util.StreamUtils;

/**
 * Pipe that creates a ZipStream.
 * 
 * For action=open, the Pipe will create a new zip, that will be written to a file or stream specified by the input message, that must be a:<ul>
 * <li>String specifying a filename</li>
 * <li>OutputStream</li>
 * <li>HttpResponse</li>
 * </ul>
 * If a parameter 'filename' is present, and the input is not OutputStream, then the parameter takes precedence over the filename specifed by the input.
 *
 * <p><b>Configuration:</b>
 * <table border="1">
 * <tr><th>attributes</th><th>description</th><th>default</th></tr>
 * <tr><td>className</td><td>nl.nn.adapterframework.compression.ZipWriterPipe</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setName(String) name}</td><td>name of the Pipe</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setMaxThreads(int) maxThreads}</td><td>maximum number of threads that may call {@link #doPipe(Object, PipeLineSession)} simultaneously</td><td>0 (unlimited)</td></tr>
 * <tr><td>{@link #setDurationThreshold(long) durationThreshold}</td><td>if durationThreshold >=0 and the duration (in milliseconds) of the message processing exceeded the value specified, then the message is logged informatory</td><td>-1</td></tr>
 * <tr><td>{@link #setGetInputFromSessionKey(String) getInputFromSessionKey}</td><td>when set, input is taken from this session key, instead of regular input</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setStoreResultInSessionKey(String) storeResultInSessionKey}</td><td>when set, the result is stored under this session key</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setPreserveInput(boolean) preserveInput}</td><td>when set <code>true</code>, the input of a pipe is restored before processing the next one</td><td>false</td></tr>
 * <tr><td>{@link #setBeforeEvent(int) beforeEvent}</td>      <td>METT eventnumber, fired just before a message is processed by this Pipe</td><td>-1 (disabled)</td></tr>
 * <tr><td>{@link #setAfterEvent(int) afterEvent}</td>        <td>METT eventnumber, fired just after message processing by this Pipe is finished</td><td>-1 (disabled)</td></tr>
 * <tr><td>{@link #setExceptionEvent(int) exceptionEvent}</td><td>METT eventnumber, fired when message processing by this Pipe resulted in an exception</td><td>-1 (disabled)</td></tr>
 * <tr><td>{@link #setForwardName(String) forwardName}</td>  <td>name of forward returned upon completion</td><td>"success"</td></tr>
 * <tr><td>{@link #setAction(String) action}</td>  <td>one of <ul>
 *   <li>open: to open a new zip file or stream</li> 
 *   <li>close: to close the zip file or stream</li> 
 *   <li>write: write the input to the zip as a new entry</li> 
 *   <li>stream: create a new zip entry, and provide an outputstream that another pipe can use to write the contents</li> 
 * </ul></td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setZipWriterHandle(String) zipWriterHandle}</td>  <td>session key used to refer to zip session. Must be used if ZipWriterPipes are nested</td><td>"zipwriterhandle"</td></tr>
 * <tr><td>{@link #setCloseStreamOnExit(boolean) closeStreamOnExit}</td>  <td>only for action="open": if set true, the stream will be closed after the zip creation is finished</td><td>true</td></tr>
 * <tr><td>{@link #setCharset(String) charset}</td><td>only for action="write": charset used to write strings to zip entries</td><td>UTF-8</td></tr>
 * </table>
 * </p>
 * <table border="1">
 * <p><b>Parameters:</b>
 * <tr><th>name</th><th>type</th><th>remarks</th></tr>
 * <tr><td>filename</td><td>string</td><td>filename of the zip or zipentry.</td></tr>
 * </table>
 * <p><b>Exits:</b>
 * <table border="1">
 * <tr><th>state</th><th>condition</th></tr>
 * <tr><td>"success"</td><td>default</td></tr>
 * <tr><td><i>{@link #setForwardName(String) forwardName}</i></td><td>if specified</td></tr>
 * </table>
 * </p>
 * 
 * @author  Gerrit van Brakel
 * @since   4.9.10
 * @version Id
 */
public class ZipWriterPipe extends FixedForwardPipe {

 	private static final String ACTION_OPEN="open";
	private static final String ACTION_WRITE="write";
	private static final String ACTION_STREAM="stream";
	private static final String ACTION_CLOSE="close";
	
	private static final String PARAMETER_FILENAME="filename";
 
 	private String action=null;
	private String zipWriterHandle="zipwriterhandle";
	private boolean closeStreamOnExit=true;
	private String charset=StreamUtil.DEFAULT_INPUT_STREAM_ENCODING;
	
	private Parameter filenameParameter=null; //used for with action=open for main filename, with action=write for entryfilename


	public void configure(PipeLine pipeline) throws ConfigurationException {
		super.configure(pipeline);
		if (!(ACTION_OPEN.equals(getAction()) || ACTION_WRITE.equals(getAction())  || ACTION_STREAM.equals(getAction()) || ACTION_CLOSE.equals(getAction()))) {
			throw new ConfigurationException(getLogPrefix(null)+"action must be either '"+ACTION_OPEN+"','"+ACTION_WRITE+"','"+ACTION_STREAM+"' or '"+ACTION_CLOSE+"'");
		}
		if (ACTION_OPEN.equals(getAction())) {
			filenameParameter=getParameterList().findParameter(PARAMETER_FILENAME);
		}
		if (ACTION_WRITE.equals(getAction()) || ACTION_STREAM.equals(getAction())) {
			filenameParameter=getParameterList().findParameter(PARAMETER_FILENAME);
			if (filenameParameter==null) {
				throw new ConfigurationException(getLogPrefix(null)+"a parameter '"+PARAMETER_FILENAME+"' is required");
			}
		}
		if (ACTION_CLOSE.equals(getAction())) {
			filenameParameter=getParameterList().findParameter(PARAMETER_FILENAME);
			if (filenameParameter!=null) {
				throw new ConfigurationException(getLogPrefix(null)+"with action ["+getAction()+"] parameter '"+PARAMETER_FILENAME+"' cannot not be configured");
			}
		}
	}

	
	protected ZipWriter getZipWriter(PipeLineSession session) {
		return ZipWriter.getZipWriter(session,getZipWriterHandle());
	}

	protected ZipWriter createZipWriter(PipeLineSession session, ParameterValueList pvl, Object input) throws PipeRunException {
		if (log.isDebugEnabled()) log.debug(getLogPrefix(session)+"opening new zipstream");
		OutputStream resultStream=null;
		if (input!=null) {
			if (input instanceof OutputStream) {
				resultStream=(OutputStream)input;
			} else if (input instanceof HttpServletResponse) {
				ParameterValue pv=pvl.getParameterValue(PARAMETER_FILENAME);
				if (pv==null) {
					throw new PipeRunException(this,getLogPrefix(session)+"parameter 'filename' not found, but required if stream is HttpServletResponse");
				}
				String filename=pv.asStringValue("download.zip");
				try {
					HttpServletResponse response=(HttpServletResponse)input;
					StreamUtil.openZipDownload(response,filename);
					resultStream=response.getOutputStream();
				} catch (IOException e) {
					throw new PipeRunException(this,getLogPrefix(session)+"cannot open download for ["+filename+"]",e);
				}
			}
		}
		if (resultStream==null) {
			String filename;
			if (filenameParameter!=null) {
				ParameterValue pv=pvl.getParameterValue(PARAMETER_FILENAME);
				filename=pv.asStringValue("");
				if (StringUtils.isEmpty(filename)) {
					throw new PipeRunException(this,getLogPrefix(session)+"parameter filename must contain a filename");
				}
			} else {
				if (input instanceof String) {
					filename=(String)input;
				} else {
					throw new PipeRunException(this,getLogPrefix(session)+"input message must contain a filename");
				}
			}			
			try {
				resultStream =new FileOutputStream(filename);
			} catch (FileNotFoundException e) {
				throw new PipeRunException(this,getLogPrefix(session)+"cannot create file ["+filename+"] a specified by input message",e);
			}
		}
		if (resultStream==null) {
			throw new PipeRunException(this,getLogPrefix(session)+"Dit not find OutputStream or HttpResponse, and could not find filename");
		}
		ZipWriter sessionData=ZipWriter.createZipWriter(session,getZipWriterHandle(),resultStream,isCloseStreamOnExit());
		return sessionData;
	}


	protected void closeZipWriterHandle(PipeLineSession session, boolean mustFind) throws PipeRunException {
		ZipWriter sessionData=getZipWriter(session);
		if (sessionData==null) {
			if (mustFind) {
				throw new PipeRunException(this,getLogPrefix(session)+"cannot find session data");
			} else {
				log.debug(getLogPrefix(session)+"did find session data, assuming already closed");
			}
		} else {
			try {
				sessionData.close();
			} catch (CompressionException e) {
				throw new PipeRunException(this,getLogPrefix(session)+"cannot close",e);
			}
		}
		session.remove(getZipWriterHandle());
	}
	
	public PipeRunResult doPipe(Object input, PipeLineSession session) throws PipeRunException {
		if (ACTION_CLOSE.equals(getAction())) {
			closeZipWriterHandle(session,true);
			return new PipeRunResult(getForward(),input);
		} 
		String msg=null;
		if (input instanceof String) {
			msg=(String)input;
		}
		ParameterResolutionContext prc = new ParameterResolutionContext(msg, session);
		ParameterValueList pvl;
		try {
			pvl = prc.getValues(getParameterList());
		} catch (ParameterException e1) {
			throw new PipeRunException(this,getLogPrefix(session)+"cannot determine filename",e1);
		}

		ZipWriter sessionData=getZipWriter(session);
		if (ACTION_OPEN.equals(getAction())) {
			if (sessionData!=null) {
				throw new PipeRunException(this,getLogPrefix(session)+"zipWriterHandle in session key ["+getZipWriterHandle()+"] is already open");		
			}
			sessionData=createZipWriter(session,pvl,input);
			return new PipeRunResult(getForward(),input);
		} 
		// from here on action must be 'write' or 'stream'
		if (sessionData==null) {
			throw new PipeRunException(this,getLogPrefix(session)+"zipWriterHandle in session key ["+getZipWriterHandle()+"] is not open");		
		} 
		String filename=(String)pvl.getParameterValue(PARAMETER_FILENAME).getValue();
		if (StringUtils.isEmpty(filename)) {
			throw new PipeRunException(this,getLogPrefix(session)+"filename cannot be empty");		
		}
		try {
			sessionData.openEntry(filename);
			if (ACTION_WRITE.equals(getAction())) {
				try {
					if (msg!=null) {
						sessionData.getZipoutput().write(msg.getBytes(getCharset()));
					} else
					if (input instanceof InputStream) {
						InputStream dataStream=(InputStream)input;
						StreamUtils.copy(dataStream, sessionData.getZipoutput());
					} else { 
						throw new PipeRunException(this,getLogPrefix(session)+"input is ["+ClassUtils.nameOf(input)+"] must be either String or InputStream");
					}
					sessionData.closeEntry();
				} catch (IOException e) {
					throw new PipeRunException(this,getLogPrefix(session)+"cannot add data to zipentry for ["+filename+"]",e);
				}
			}
			if (ACTION_STREAM.equals(getAction())) {
				PipeRunResult prr = new PipeRunResult(getForward(),sessionData.getZipoutput());
				return prr;
			}
		} catch (CompressionException e) {
			throw new PipeRunException(this,getLogPrefix(session)+"cannot add zipentry for ["+filename+"]",e);
		}
		return new PipeRunResult(getForward(),input);
	}

	protected String getLogPrefix(PipeLineSession session) {
		return super.getLogPrefix(session)+"action ["+getAction()+"] ";
	}
	
	public void setCloseStreamOnExit(boolean b) {
		closeStreamOnExit = b;
	}
	public boolean isCloseStreamOnExit() {
		return closeStreamOnExit;
	}

	public void setCharset(String string) {
		charset = string;
	}
	public String getCharset() {
		return charset;
	}

	public void setZipWriterHandle(String string) {
		zipWriterHandle = string;
	}
	public String getZipWriterHandle() {
		return zipWriterHandle;
	}

	public void setAction(String string) {
		action = string;
	}
	public String getAction() {
		return action;
	}
}