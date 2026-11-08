package top.zainzh.rearscreen;

import java.io.IOException;

/** Validation for reader lines which arrive after their input epoch has been paused. */
final class RearInputReaderLineProtocol {
    private RearInputReaderLineProtocol() {}

    /**
     * A valid F frame can already be buffered on stdout when a newer S/R command takes effect.
     * While Java has not accepted the matching READY2, validate and discard that stale frame;
     * malformed frames remain protocol errors.
     */
    static boolean discardFrameIfNotReady(String[] parts, boolean ready, int slots) throws IOException {
        if (ready || parts.length == 0 || !"F".equals(parts[0])) return false;
        if (parts.length < 3) throw new IOException("Invalid rear frame");
        try {
            Long.parseLong(parts[1]);
            int count = Integer.parseInt(parts[2]);
            if (count < 0 || count > slots || parts.length != count + 3) throw new IOException("Invalid rear frame");
            for (int i = 0; i < count; i++) {
                String[] contact = parts[i + 3].split(":");
                if (contact.length != 4) throw new IOException("Invalid rear contact");
                int slot = Integer.parseInt(contact[0]);
                Integer.parseInt(contact[1]);
                float x = Float.parseFloat(contact[2]);
                float y = Float.parseFloat(contact[3]);
                if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y))
                    throw new IOException("Invalid rear contact coordinate");
                if (slot < 0 || slot >= slots) throw new IOException("Invalid rear contact slot");
            }
            return true;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid rear frame", e);
        }
    }
}
