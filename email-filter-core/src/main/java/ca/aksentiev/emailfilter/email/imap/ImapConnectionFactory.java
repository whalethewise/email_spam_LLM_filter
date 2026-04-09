package ca.aksentiev.emailfilter.email.imap;

import java.util.Properties;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.ImapProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.springframework.stereotype.Component;

/**
 * Creates IMAP connections using shared {@link ImapProperties}.
 * Used by both {@link ImapIdleMonitor} (real-time IDLE) and
 * {@link ca.aksentiev.emailfilter.scan.ScanService} (on-demand scan).
 */
@Component
public class ImapConnectionFactory {

    private final ImapProperties imapProperties;

    public ImapConnectionFactory(ImapProperties imapProperties) {
        this.imapProperties = imapProperties;
    }

    /**
     * Opens an authenticated IMAPS connection to the given account.
     * Caller is responsible for closing the returned {@link Store}.
     */
    public Store connect(AccountProperties.Account account) throws MessagingException {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "imaps");
        props.setProperty("mail.imaps.host", account.getHost());
        props.setProperty("mail.imaps.port", String.valueOf(imapProperties.getPort()));
        props.setProperty("mail.imaps.timeout", String.valueOf(imapProperties.getSocketTimeout()));
        props.setProperty("mail.imaps.connectiontimeout", String.valueOf(imapProperties.getConnectionTimeout()));
        props.setProperty("mail.imaps.usesocketchannels", "false");
        props.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        props.setProperty("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3");

        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(account.getHost(), account.getUsername(), account.getPassword());
        return store;
    }
}
