// =================================================================                                                                   
// Copyright (C) 2011-2013 Pierre Lison (plison@ifi.uio.no)                                                                            
//                                                                                                                                     
// This library is free software; you can redistribute it and/or                                                                       
// modify it under the terms of the GNU Lesser General Public License                                                                  
// as published by the Free Software Foundation; either version 2.1 of                                                                 
// the License, or (at your option) any later version.                                                                                 
//                                                                                                                                     
// This library is distributed in the hope that it will be useful, but                                                                 
// WITHOUT ANY WARRANTY; without even the implied warranty of                                                                          
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU                                                                    
// Lesser General Public License for more details.                                                                                     
//                                                                                                                                     
// You should have received a copy of the GNU Lesser General Public                                                                    
// License along with this program; if not, write to the Free Software                                                                 
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA                                                                           
// 02111-1307, USA.                                                                                                                    
// =================================================================                                                                   

package opendial.utils;

import java.io.IOException;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import opendial.arch.DialException;
import opendial.utils.Logger;

/**
 * Utility functions for manipulating XML content
 *
 * @author  Pierre Lison (plison@ifi.uio.no)
 * @version $Date::                      $
 *
 */
public class XMLUtils {

	// logger
	static Logger log = new Logger("XMLUtils", Logger.Level.NORMAL);


	/**
	 * Opens the XML document referenced by the filename, and returns it
	 * 
	 * @param filename the filename
	 * @return the XML document
	 * @throws DialException
	 */
	public static Document getXMLDocument (String filename) throws DialException {

		log.info("parsing file: " + filename);
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

		try {
			DocumentBuilder builder = factory.newDocumentBuilder();

			builder.setErrorHandler(new XMLErrorHandler());
			Document doc = builder.parse(new InputSource(filename));
			log.debug("XML parsing of file: " + filename + " successful!");
			return doc;
		}
		catch (SAXException e) {
			log.warning("Reading aborted: \n" + e.getMessage());
		} 
		catch (ParserConfigurationException e) {
			log.warning(e.getMessage());
		} 
		catch (IOException e) {
			log.warning(e.getMessage());
			throw new DialException(e.getMessage());
		}
		return null;
	}



	/**
	 * If the XML node contains a "file" attribute containing a filename, returns it.
	 * Else, throws an exception
	 * 
	 * @param node the XML node
	 * @return the referenced filename
	 * @throws DialException 
	 */
	public static String getReference(Node node) throws DialException {

		if (node.hasAttributes() && node.getAttributes().getNamedItem("file") != null) {
			String filename = node.getAttributes().getNamedItem("file").getNodeValue();
			return filename;
		}
		else {
			throw new DialException("Not file attribute in which to extract the reference");
		}
	}


	/**
	 * Returns the main node of the XML document
	 * 
	 * @param doc the XML document
	 * @param topTag the expected top tag of the document
	 * @return the main node
	 * @throws DialException if node is ill-formatted
	 */
	public static Node getMainNode (Document doc, String topTag) throws DialException {

		NodeList topList = doc.getChildNodes();
		if (topList.getLength() == 1 && topList.item(0).getNodeName().equals(topTag)) {
			Node topNode = topList.item(0);
			return topNode;
		}
		else if (topList.getLength() == 0) {
			throw new DialException("Document is empty");
		}
		else  {
			throw new DialException("Document contains other tags than \"" + topTag + "\": " + topList.item(0).getNodeName());
		}
	}



	/**
	 * Validates a XML document containing a specification of a dialogue domain.
	 * Returns true if the XML document is valid, false otherwise.
	 * 
	 * @param dialSpecs the domain file
	 * @param schemaFile the schema file
	 * @return true if document is validated, false otherwise
	 * @throws DialException if problem appears when parsing XML
	 */
	public static boolean validateXML(String dialSpecs, String schemaFile) throws DialException {

		log.debug ("Checking the validation of file " + dialSpecs + " against XML schema " + schemaFile + "...");
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

		try {
			SchemaFactory schema = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
			factory.setSchema(schema.newSchema(new Source[] {new StreamSource(schemaFile)}));
			DocumentBuilder builder = factory.newDocumentBuilder();

			try {
				builder.setErrorHandler(new XMLErrorHandler());
				Document doc = builder.parse(new InputSource(dialSpecs));
				log.debug("XML parsing of file: " + dialSpecs + " successful!");

				// extracting included files, and validating them as well
				String rootpath = dialSpecs.substring(0, dialSpecs.lastIndexOf("//")+1);
				Vector<String> includedFiles = extractIncludedFiles(doc);
				for (String file : includedFiles) {
					boolean validation = validateXML(rootpath+file, schemaFile);
					if (!validation) {
						return false;
					}
				}
			}
			catch (Exception e) {
				throw new DialException(e.getMessage());
			}
			return true;
		}
		catch (SAXException e) {
			log.warning("Validation aborted: \n" + e.getMessage());
			return false;
		} catch (ParserConfigurationException e) {
			log.warning(e.getMessage());
			return false;
		} 
	}


	/**
	 * Extract included filenames in the XML document
	 * 
	 * @param xmlDocument the XML document
	 * @return the filenames to include
	 */
	private static Vector<String> extractIncludedFiles(Document xmlDocument) {

		Vector<String> includedFiles = new Vector<String>();

		NodeList top = xmlDocument.getChildNodes();
		for (int i = 0 ; i < top.getLength(); i++) {
			Node topNode = top.item(i);
			NodeList firstElements = topNode.getChildNodes();
			for (int j = 0 ; j < firstElements.getLength() ; j++) {
				Node node = firstElements.item(j);
				if (node.hasAttributes() && node.getAttributes().getNamedItem("file") != null) {
					String fileName = node.getAttributes().getNamedItem("file").getNodeValue();
					includedFiles.add(fileName);
				}
			}
		}
		return includedFiles;
	}


}





/**
 * Small error handler for XML syntax errors.
 *
 * @author  Pierre Lison (plison@ifi.uio.no)
 * @version $Date::                      $
 *
 */
final class XMLErrorHandler extends DefaultHandler {

	static Logger log = new Logger("XMLErrorHandler", Logger.Level.NORMAL);

	public void error (SAXParseException e) throws SAXParseException { 
		log.warning("Parsing error: "+e.getMessage());
		throw e;
	}

	public void warning (SAXParseException e) { 
		log.warning("Parsing problem: "+e.getMessage());
	}

	public void fatalError (SAXParseException e) { 
		log.severe("Parsing error: "+e.getMessage()); 
		log.severe("Cannot continue."); 
		System.exit(1);
	}

}