package shop.billing;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class ExportService {

    public List<Invoice> importXml(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(in);

        List<Invoice> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("invoice");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            result.add(new Invoice(
                Long.parseLong(el.getAttribute("id")),
                el.getAttribute("customer"),
                Long.parseLong(el.getAttribute("amountCents"))));
        }
        return result;
    }
}
