package sslify;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;
import org.jetbrains.annotations.NotNull;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Formatter;

@Slf4j
@Singleton
public class CertInfoFactoryLDAPImpl implements CertInfoFactory {
    private static final String PROP_BASE_DN_USER = "queries.basedn.user";
    private static final String PROP_FILTER_USER = "queries.filter.user";
    private static final String PROP_BASE_DN_GROUPS = "queries.basedn.groups";
    private static final String PROP_FILTER_GROUPS = "queries.filter.groups";
    private static final String[]
            USER_ATTRIBUTES = {"cn", "uid", "mail"};
    private static final String[] GROUP_ATTRIBUTES = {"cn"};

    private final ConfigProperties props;
    private final Cache cache;

    @Inject
    CertInfoFactoryLDAPImpl(final ConfigPropertiesFactory configPropertiesFactory, final CacheFactory cacheFactory)
            throws FileNotFoundException, ConfigProperties.ConfigLoadingException {
        this.props = configPropertiesFactory.get(ConfigProperties.Domain.LDAP);
        this.cache = cacheFactory.getCache(CacheFactory.Domain.LDAP);
    }

    @NotNull
    private CertInfo download(@NonNull final String user) throws NamingException {
        final Formatter userFormatter = new Formatter();
        final Formatter groupFormatter = new Formatter();

        final String cn, uid, mail;
        final ArrayList<String> groups = new ArrayList<String>();

        final InitialDirContext dirContext = new InitialDirContext(this.props);

        final SearchControls user_controls = new SearchControls();
        user_controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        user_controls.setReturningAttributes(USER_ATTRIBUTES);

        final SearchControls groups_controls = new SearchControls();
        groups_controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        groups_controls.setReturningAttributes(GROUP_ATTRIBUTES);

        final String user_query =
                userFormatter.format(
                        props.getProperty(PROP_FILTER_USER), user).toString();
        final String groups_query =
                groupFormatter.format(
                        props.getProperty(PROP_FILTER_GROUPS), user).toString();

        final NamingEnumeration<SearchResult> user_results =
                dirContext.search(props.getProperty(PROP_BASE_DN_USER),
                        user_query, user_controls);
        if (!user_results.hasMore())
            throw new MissingUserException();
        final Attributes attributes = user_results.next().getAttributes();
        if (user_results.hasMore())
            throw new TooManyUsersException();

        try {
            cn = attributes.get("cn").get().toString();
            uid = attributes.get("uid").get().toString();
            mail = attributes.get("mail").get().toString();
        } catch (NullPointerException e) {
            throw new MissingDetailsException();
        }

        final NamingEnumeration<SearchResult> groups_results =
                dirContext.search(props.getProperty(PROP_BASE_DN_GROUPS),
                        groups_query, groups_controls);
        while (groups_results.hasMore()) {
            final SearchResult group = groups_results.next();
            final String groupCn = group.getAttributes().get("cn").get().toString();
            groups.add(groupCn);
        }

        dirContext.close();

        return new CertInfo(cn, uid, mail, groups.toArray(new String[groups.size()]));
    }

    /* Optimisticly non-synchronized */
    @NotNull
    @Override
    public CertInfo get(@NonNull final String user) throws NamingException {
        final Element element = cache.get(user);
        if (element != null) {
            CertInfo cached = (CertInfo) element.getValue();
            if (cached == null) {
                log.debug("cached failure:'{}'", user);
                throw new CachedFailureException();
            } else {
                log.debug("cached user:'{}'", user);
                return cached;
            }
        }

        log.debug("uncached user:'{}'", user);
        CertInfo grabbed = null;
        try {
            grabbed = download(user);
        } finally {
            cache.put(new Element(user, grabbed));
        }
        return grabbed;
    }
}
