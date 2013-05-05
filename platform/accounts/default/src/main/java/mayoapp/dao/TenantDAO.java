package mayoapp.dao;

import java.util.List;

import org.mayocat.accounts.model.Tenant;
import org.mayocat.accounts.store.jdbi.mapper.TenantMapper;
import org.mayocat.addons.store.dbi.AddonsHelper;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.BindBean;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.customizers.RegisterMapper;
import org.skife.jdbi.v2.sqlobject.mixins.Transactional;
import org.skife.jdbi.v2.sqlobject.stringtemplate.UseStringTemplate3StatementLocator;

@RegisterMapper(TenantMapper.class)
@UseStringTemplate3StatementLocator
public abstract class TenantDAO implements EntityDAO<Tenant>, Transactional<TenantDAO>, AddonsDAO<Tenant>
{
    @SqlUpdate
    (

    )
    public abstract void updateConfiguration(@BindBean("tenant") Tenant tenant, @Bind("version") Integer version,
            @Bind("data") String configuration);

    @SqlUpdate
    (

    )
    public abstract void create(@BindBean("tenant") Tenant tenant,  @Bind("version") Integer version,
            @Bind("data") String configuration);

    @SqlQuery
    (

    )
    public abstract Tenant findByDefaultHost(@Bind("host") String host);

    @SqlQuery
    (

    )
    public abstract List<Tenant> findAll(@Bind("number") Integer number, @Bind("offset") Integer offset);

    public void createOrUpdateAddons(Tenant entity)
    {
        AddonsHelper.createOrUpdateAddons(this, entity);
    }
}