
package net.sf.ehcache.server.soap.jaxws;

import javax.xml.ws.WebFault;


/**
 * This class was generated by the JAXWS SI.
 * JAX-WS RI 2.1-02/02/2007 03:56 AM(vivekp)-FCS
 * Generated source version: 2.1
 * 
 */
@WebFault(name = "IllegalStateException", targetNamespace = "http://soap.server.ehcache.sf.net/")
public class IllegalStateException_Exception
    extends Exception
{

    /**
     * Java type that goes as soapenv:Fault detail element.
     * 
     */
    private IllegalStateException faultInfo;

    /**
     * 
     * @param faultInfo
     * @param message
     */
    public IllegalStateException_Exception(String message, IllegalStateException faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @param faultInfo
     * @param message
     * @param cause
     */
    public IllegalStateException_Exception(String message, IllegalStateException faultInfo, Throwable cause) {
        super(message, cause);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @return
     *     returns fault bean: net.sf.ehcache.server.soap.jaxws.IllegalStateException
     */
    public IllegalStateException getFaultInfo() {
        return faultInfo;
    }

}
