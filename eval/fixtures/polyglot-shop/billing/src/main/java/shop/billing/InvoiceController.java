package shop.billing;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/billing")
public class InvoiceController {

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final ExportService exports;
    private final TokenService tokens;

    public InvoiceController(InvoiceRepository invoices, CustomerRepository customers,
                             ExportService exports, TokenService tokens) {
        this.invoices = invoices;
        this.customers = customers;
        this.exports = exports;
        this.tokens = tokens;
    }

    @GetMapping("/invoices")
    public List<Invoice> list(@RequestParam String customer) {
        return invoices.findByCustomer(customer);
    }

    @GetMapping("/customers/{id}")
    public Customer customer(@PathVariable long id) {
        return customers.findById(id);
    }

    @PostMapping("/import")
    public int importInvoices(@RequestParam("file") MultipartFile file) throws Exception {
        return exports.importXml(file.getInputStream()).size();
    }

    @PostMapping("/customers/{id}/portal-link")
    public String portalLink(@PathVariable long id) {
        return "https://billing.example.com/portal?token=" + tokens.newPortalToken(id);
    }
}
