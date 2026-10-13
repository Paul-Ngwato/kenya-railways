import com.ueab.sac.service.whatsapp.WhatsAppEventParser;

public class DebugParse {
    public static void main(String[] args) {
        WhatsAppEventParser p = new WhatsAppEventParser();
        String[] msgs = {
            "CAT 1 in BIT 2104 on 25/09/2026 at 10am in Lab B.",
            "End semester EXAM for Database Systems will be on 3rd Dec at 9:30 am in Main Hall."
        };
        for (String m : msgs) {
            System.out.println("MSG: " + m);
            var r = p.localParse(m);
            if (r.isPresent()) {
                var ex = r.get();
                System.out.println("  type=" + ex.type() + " date=" + ex.date() + " time=" + ex.time() + " venue=" + ex.venue());
            } else {
                System.out.println("  EMPTY");
            }
        }
    }
}
