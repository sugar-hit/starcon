'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  History,
  MapPin,
  Phone,
  Plus,
  RefreshCw,
  Save,
  Star,
  Trash2,
  TriangleAlert,
  UserRound,
} from 'lucide-react';
import type {
  PartyAddress,
  PartyAddressVersion,
  PartyContact,
  PartyRow,
} from '../../../../../packages/api-contracts/src/index';
import { Editor, Field, control } from '../common/fields';
import {
  ADDRESS_COUNTRIES,
  ADDRESS_USAGES,
  contactBlockLabel,
  contactDisplayName,
  contactEmptyHint,
  contactInputProblem,
  createAddress,
  createContact,
  deleteAddress,
  deleteContact,
  addressEmptyHint,
  addressInputProblem,
  addressUsagesProblem,
  addressVersionEmptyHint,
  emptyAddressInput,
  emptyContactInput,
  errorText,
  fetchAddressVersions,
  fetchAddresses,
  fetchContacts,
  removedUsages,
  saveAddress,
  saveAddressUsages,
  saveContact,
  selectionBlockLabel,
  setAddressDefault,
  setPrimaryContact,
  toAddressInput,
  toContactInput,
  usageHint,
  usageLabel,
  type AddressListQuery,
} from './party-contact-model';
import { fetchParties as fetchPartyRows } from './party-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.6.3.1 地址与联系人工作区。
 *
 * <p>页面按「先选主体 → 再维护它的地址与联系人」的顺序排，因为地址与联系人的归属
 * 就是往来单位，没有主体的地址是无意义的（后端用复合外键把这件事在 DB 层挡住了）。
 *
 * <ul>
 *   <li><b>主体选择</b> —— 复用 3.6.1.1 的往来单位列表（同一个端点，不是另做一套演示），
 *       选中后下面两块都跟着它走。
 *   <li><b>地址</b> —— 分页 + 搜索 + 用途 / 国家筛选。每行直接显示<b>用途徽章与默认标记</b>：
 *       「哪个是默认提货地址」要在列表上一眼看见，否则 10 物流发货时要逐条点开详情找。
 *   <li><b>地址历史版本</b> —— 点开一条地址看它的版本链，并显示每一版的单行地址。
 *       这是蓝图「地址变更留历史版本」在界面上的落点，也是「改地址不影响已发生单据」
 *       让人能亲眼核对的地方。
 *   <li><b>联系人</b> —— 默认联系人排在最前，可一键改默认；删除与改默认是两个动作。
 * </ul>
 *
 * <p>错误态 / 空态 / 加载态三种都在：无数据的引导语与筛选后的空结果**用不同文案**
 * （无数据时告诉运营「该建什么」，筛选为空时告诉运营「条件太窄」）。
 */
export function PartyContactsWorkspace() {
  const { session } = useIdentity();
  const writable = (session?.permissions ?? []).includes('catalog.spu.update');

  const [parties, setParties] = useState<PartyRow[]>([]);
  const [partyKeyword, setPartyKeyword] = useState('');
  const [partyId, setPartyId] = useState<string | null>(null);
  const [party, setParty] = useState<PartyRow | null>(null);

  const [addresses, setAddresses] = useState<PartyAddress[]>([]);
  const [addressStats, setAddressStats] = useState({ total: 0, defaultCount: 0, unassignedCount: 0 });
  const [addressQuery, setAddressQuery] = useState<AddressListQuery>({
    keyword: '',
    usage: '',
    countryCode: '',
    includeDeleted: false,
    page: 0,
    size: PAGE_SIZE,
  });

  const [contacts, setContacts] = useState<PartyContact[]>([]);
  const [contactKeyword, setContactKeyword] = useState('');
  const [contactPrimaryOnly, setContactPrimaryOnly] = useState(false);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [editingAddress, setEditingAddress] = useState<PartyAddress | null>(null);
  const [addressForm, setAddressForm] = useState(emptyAddressInput());
  const [editingContact, setEditingContact] = useState<PartyContact | null>(null);
  const [contactForm, setContactForm] = useState(emptyContactInput());
  const [history, setHistory] = useState<{ address: PartyAddress; versions: PartyAddressVersion[] } | null>(null);

  // ------------------------------------------------------------ 主体
  const loadParties = useCallback(async () => {
    setLoading(true);
    try {
      const page = await fetchPartyRows({
        keyword: partyKeyword,
        partyType: '',
        status: '',
        roleType: '',
        countryCode: '',
        includeDeleted: false,
        usable: null,
        page: 0,
        size: 100,
      });
      setParties(page.items);
      setParty((current) => {
        if (current && page.items.some((item) => item.id === current.id)) return current;
        return page.items[0] ?? null;
      });
      setError(null);
    } catch (e) {
      setError(errorText(e, '往来单位加载失败：地址与联系人要挂在某个往来单位下面'));
    } finally {
      setLoading(false);
    }
  }, [partyKeyword]);

  // ------------------------------------------------------------ 地址与联系人
  const loadAddresses = useCallback(async (id: string, query: AddressListQuery) => {
    const page = await fetchAddresses(id, query);
    setAddresses(page.items);
    setAddressStats({
      total: page.total,
      defaultCount: page.defaultCount,
      unassignedCount: page.unassignedCount,
    });
  }, []);

  const loadContacts = useCallback(
    async (id: string, keyword: string, primaryOnly: boolean) => {
      setContacts(await fetchContacts(id, keyword, primaryOnly));
    },
    [],
  );

  useEffect(() => {
    void loadParties();
  }, [loadParties]);

  useEffect(() => {
    if (!partyId) return;
    void loadAddresses(partyId, addressQuery)
      .then(() => loadContacts(partyId, contactKeyword, contactPrimaryOnly))
      .catch((e) => setActionError(errorText(e, '地址与联系人加载失败')));
  }, [partyId, addressQuery, loadAddresses, loadContacts, contactKeyword, contactPrimaryOnly]);

  const refresh = useCallback(() => {
    if (!partyId) return;
    void loadAddresses(partyId, addressQuery)
      .then(() => loadContacts(partyId, contactKeyword, contactPrimaryOnly))
      .then(() => setNotice(null))
      .catch((e) => setActionError(errorText(e, '刷新失败')));
  }, [partyId, addressQuery, contactKeyword, contactPrimaryOnly, loadAddresses, loadContacts]);

  // ------------------------------------------------------------ 动作
  const guard = async (work: () => Promise<void>, failure: string) => {
    try {
      await work();
      setActionError(null);
      setNotice(null);
    } catch (e) {
      setActionError(errorText(e, failure));
    }
  };

  const submitAddress = () => {
    if (!partyId) return;
    const problem =
      addressInputProblem(addressForm) ?? addressUsagesProblem(addressForm.usages ?? []);
    if (problem) {
      setActionError(problem);
      return;
    }
    const payload = { ...addressForm, usages: addressForm.usages ?? [] };
    void guard(async () => {
      if (editingAddress) {
        const result = await saveAddress(partyId, editingAddress.id, payload);
        setNotice(
          `地址已保存（当前第 ${result.latestVersionNo} 版）。` +
            '已发生单据上的地址是冻结快照，不受这次修改影响。',
        );
      } else {
        await createAddress(partyId, payload);
        setNotice('地址已建档，并写入第 1 版历史。');
      }
      setEditingAddress(null);
      refresh();
    }, '地址保存失败');
  };

  const submitUsages = (address: PartyAddress, usages: { usage: string; isDefault: boolean }[]) => {
    if (!partyId) return;
    const problem = addressUsagesProblem(usages);
    if (problem) {
      setActionError(problem);
      return;
    }
    void guard(async () => {
      // 走 /usages 而不是主保存：只改标签不该在版本链上留下一条内容没变的行。
      await saveAddressUsages(partyId, address.id, usages, address.version);
      setNotice('用途已更新（地址内容没动，所以没有新增历史版本）。');
      refresh();
    }, '用途保存失败');
  };

  const toggleDefault = (address: PartyAddress, usage: string, isDefault: boolean) => {
    if (!partyId) return;
    void guard(async () => {
      await setAddressDefault(partyId, address.id, usage, isDefault, address.version);
      setNotice(
        isDefault
          ? `已设为默认${usageLabel(usage)}（每个用途只有一个默认，原来那条自动取消）。`
          : `已取消默认${usageLabel(usage)}标记。`,
      );
      refresh();
    }, '默认地址设置失败');
  };

  const removeAddress = (address: PartyAddress) => {
    if (!partyId) return;
    const defaults = address.defaultUsages ?? [];
    if (defaults.length > 0) {
      // 不发请求：后端也会拒，但界面先说清该做什么更省一次往返。
      setActionError(
        `这条地址还是${defaults.map(usageLabel).join('、')}的默认地址，请先把默认改到别的地址再删除。`,
      );
      return;
    }
    void guard(async () => {
      await deleteAddress(partyId, address.id, address.version);
      setNotice('地址已删除（软删除：行与历史版本都保留，已发生单据照常显示当时的地址）。');
      setEditingAddress(null);
      refresh();
    }, '地址删除失败');
  };

  const openHistory = (address: PartyAddress) => {
    if (!partyId) return;
    void guard(async () => {
      setHistory({ address, versions: await fetchAddressVersions(partyId, address.id) });
    }, '历史版本加载失败');
  };

  const submitContact = () => {
    if (!partyId) return;
    const problem = contactInputProblem(contactForm);
    if (problem) {
      setActionError(problem);
      return;
    }
    void guard(async () => {
      if (editingContact) {
        await saveContact(partyId, editingContact.id, contactForm);
        setNotice('联系人已保存。');
      } else {
        await createContact(partyId, contactForm);
        setNotice('联系人已建档。');
      }
      setEditingContact(null);
      refresh();
    }, '联系人保存失败');
  };

  const togglePrimary = (contact: PartyContact, isPrimary: boolean) => {
    if (!partyId) return;
    void guard(async () => {
      await setPrimaryContact(partyId, contact.id, isPrimary, contact.version);
      setNotice(isPrimary ? '已设为默认联系人（原来的默认自动取消）。' : '已取消默认联系人。');
      refresh();
    }, '默认联系人设置失败');
  };

  const removeContact = (contact: PartyContact) => {
    if (!partyId) return;
    if (contact.isPrimary) {
      setActionError('这条还是默认联系人，请先把默认联系人改到别的联系人再删除。');
      return;
    }
    void guard(async () => {
      await deleteContact(partyId, contact.id, contact.version);
      setNotice('联系人已删除。');
      setEditingContact(null);
      refresh();
    }, '联系人删除失败');
  };

  const addressFiltered =
    Boolean(addressQuery.keyword.trim()) ||
    Boolean(addressQuery.usage) ||
    Boolean(addressQuery.countryCode.trim()) ||
    addressQuery.includeDeleted;
  const contactFiltered = Boolean(contactKeyword.trim()) || contactPrimaryOnly;
  const addressPages = Math.max(1, Math.ceil(addressStats.total / PAGE_SIZE));
  const primaryContact = contacts.find((contact) => contact.isPrimary);
  const unusedUsageCount = useMemo(
    () =>
      ADDRESS_USAGES.filter((usage) =>
        addresses.some((address) => address.defaultUsages?.includes(usage.value)),
      ).length,
    [addresses],
  );

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <h1 className="text-h-20 font-semibold tracking-tight">地址与联系人</h1>
        <p className="text-copy-13 text-muted-foreground">
          往来单位的收发货地址与带姓名的联系人。默认收货 / 发货 / 提货地址按<b>用途分别</b>设置
          ——06 采购取发货与收货地址、10 物流取提货地址；地址内容每改一次会留一版历史，
          <b>已发生单据上的地址是冻结快照，不受这里修改的影响</b>。
        </p>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="地址总数" value={addressStats.total} icon={<MapPin className="h-4 w-4" />} />
        <StatCard
          label="已设默认"
          value={addressStats.defaultCount}
          delta={unusedUsageCount}
          comparisonLabel={`个用途已有默认（共 ${ADDRESS_USAGES.length} 个）`}
          polarity={unusedUsageCount === ADDRESS_USAGES.length ? 'positive' : 'neutral'}
        />
        <StatCard
          label="未标用途"
          value={addressStats.unassignedCount}
          icon={<TriangleAlert className="h-4 w-4" />}
          polarity={addressStats.unassignedCount > 0 ? 'negative' : 'positive'}
        />
        <StatCard
          label="联系人"
          value={contacts.length}
          delta={primaryContact ? 1 : 0}
          comparisonLabel="其中默认联系人"
          icon={<UserRound className="h-4 w-4" />}
        />
      </section>

      {error && (
        <SectionCard title="加载失败">
          <div className="text-destructive space-y-2 text-sm">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void loadParties()}>
              <RefreshCw className="h-4 w-4" />
              重试
            </Button>
          </div>
        </SectionCard>
      )}

      {notice && (
        <p className="text-muted-foreground text-sm" role="status">
          {notice}
        </p>
      )}

      {actionError && (
        <SectionCard title="操作未完成">
          <p className="text-destructive text-sm">{actionError}</p>
        </SectionCard>
      )}

      <SectionCard
        title="往来单位"
        description="地址与联系人都挂在某个往来单位下面，没有主体的地址是无意义的。选一个往下看。"
      >
        <div className="space-y-3">
          <Field label="关键词" hint="编码 / 名称 / 邮箱 / 电话">
            <input
              className={control}
              value={partyKeyword}
              placeholder="搜对方公司"
              onChange={(e) => setPartyKeyword(e.target.value)}
            />
          </Field>
          {loading ? (
            <p className="text-muted-foreground text-sm">加载中…</p>
          ) : parties.length === 0 ? (
            <EmptyState
              title="没有往来单位"
              description="先到「往来单位」建一个主体，再回来维护它的地址与联系人。"
            />
          ) : (
            <div className="flex flex-wrap gap-2">
              {parties.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setPartyId(item.id)}
                  className={`rounded-md border px-3 py-2 text-sm ${
                    partyId === item.id
                      ? 'border-primary bg-primary/10 font-medium'
                      : 'border-control-border hover:border-primary/50'
                  }`}
                >
                  {item.code} · {item.name}
                  {item.usableBlockCode && (
                    <span className="text-muted-foreground ml-2 text-xs">
                      {item.usableBlockCode === 'PARTY_INACTIVE'
                        ? '已停用'
                        : item.usableBlockCode === 'PARTY_BLACKLISTED'
                          ? '已拉黑'
                          : '已删除'}
                    </span>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {partyId && (
        <>
          <SectionCard
            title="收发货地址"
            description="每个行直接显示它承担的用途与默认标记：默认收货 / 发货 / 提货地址是三条独立的默认，10 物流取的是提货地址。"
            extra={
              writable ? (
                <Button
                  size="sm"
                  onClick={() => {
                    setEditingAddress(null);
                    setAddressForm(emptyAddressInput());
                    setActionError(null);
                  }}
                >
                  <Plus className="h-4 w-4" />
                  新建地址
                </Button>
              ) : undefined
            }
          >
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <Field label="关键词" hint="地址名 / 街道 / 城市 / 省州 / 邮编">
                  <input
                    className={control}
                    value={addressQuery.keyword}
                    placeholder="搜街道或城市"
                    onChange={(e) => setAddressQuery({ ...addressQuery, keyword: e.target.value, page: 0 })}
                  />
                </Field>
                <Field label="用途">
                  <select
                    className={control}
                    value={addressQuery.usage}
                    onChange={(e) => setAddressQuery({ ...addressQuery, usage: e.target.value, page: 0 })}
                  >
                    <option value="">全部用途</option>
                    {ADDRESS_USAGES.map((usage) => (
                      <option key={usage.value} value={usage.value}>
                        {usage.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="国家">
                  <select
                    className={control}
                    value={addressQuery.countryCode}
                    onChange={(e) => setAddressQuery({ ...addressQuery, countryCode: e.target.value, page: 0 })}
                  >
                    <option value="">全部国家</option>
                    {ADDRESS_COUNTRIES.map((country) => (
                      <option key={country.value} value={country.value}>
                        {country.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="已删除" hint="软删除的地址默认不显示">
                  <input
                    type="checkbox"
                    checked={addressQuery.includeDeleted}
                    onChange={(e) =>
                      setAddressQuery({ ...addressQuery, includeDeleted: e.target.checked, page: 0 })
                    }
                  />
                </Field>
              </div>

              {addresses.length === 0 ? (
                <EmptyState title="没有地址" description={addressEmptyHint(addressFiltered)} />
              ) : (
                <div className="space-y-2">
                  {addresses.map((address) => (
                    <div
                      key={address.id}
                      className="border-control-border space-y-2 rounded-md border p-3"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{address.label ?? '未命名地址'}</span>
                        {(address.usages ?? []).map((usage) => (
                          <Badge key={usage} tone={address.defaultUsages?.includes(usage) ? 'success' : 'info'}>
                            {usageLabel(usage)}
                            {address.defaultUsages?.includes(usage) ? '（默认）' : ''}
                          </Badge>
                        ))}
                        {address.usages?.length === 0 && <Badge tone="warning">未标用途</Badge>}
                        {address.selectionBlockCode && (
                          <Badge tone="warning">{selectionBlockLabel(address.selectionBlockCode)}</Badge>
                        )}
                        <span className="text-muted-foreground ml-auto text-xs">
                          第 {address.latestVersionNo} 版 · 共 {address.versionCount} 版
                        </span>
                      </div>
                      <p className="text-sm">{address.oneLine}</p>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => void openHistory(address)}
                          disabled={address.deleted}
                        >
                          <History className="h-4 w-4" />
                          历史版本
                        </Button>
                        {writable && !address.deleted && (
                          <>
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => {
                                setEditingAddress(address);
                                setAddressForm(toAddressInput(address));
                                setActionError(null);
                              }}
                            >
                              编辑
                            </Button>
                            {ADDRESS_USAGES.filter(
                              (usage) => address.usages?.includes(usage.value),
                            ).map((usage) => {
                              const isDefault = address.defaultUsages?.includes(usage.value) ?? false;
                              return (
                                <Button
                                  key={usage.value}
                                  variant="outline"
                                  size="sm"
                                  title={usageHint(usage.value)}
                                  onClick={() => toggleDefault(address, usage.value, !isDefault)}
                                >
                                  <Star className="h-4 w-4" />
                                  {isDefault ? `取消默认${usage.label}` : `设为默认${usage.label}`}
                                </Button>
                              );
                            })}
                            {address.usages?.length === 0 && (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => void submitUsages(address, [{ usage: 'SHIP_TO', isDefault: false }])}
                              >
                                标为收货地址
                              </Button>
                            )}
                            <Button
                              variant="outline"
                              size="sm"
                              className="text-destructive"
                              onClick={() => removeAddress(address)}
                            >
                              <Trash2 className="h-4 w-4" />
                              删除
                            </Button>
                          </>
                        )}
                      </div>
                      {address.selectionBlockMessage && address.selectionBlockCode && (
                        <p className="text-muted-foreground text-xs">{address.selectionBlockMessage}</p>
                      )}
                    </div>
                  ))}
                </div>
              )}

              {addressStats.total > PAGE_SIZE && (
                <div className="flex items-center gap-2 text-sm">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={addressQuery.page === 0}
                    onClick={() => setAddressQuery({ ...addressQuery, page: addressQuery.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground">
                    第 {addressQuery.page + 1} / {addressPages} 页 · 共 {addressStats.total} 条
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={addressQuery.page + 1 >= addressPages}
                    onClick={() => setAddressQuery({ ...addressQuery, page: addressQuery.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              )}
            </div>
          </SectionCard>

          <SectionCard
            title="联系人"
            description="默认联系人排在最前：06 采购问交期、11 售后约退货要找的通常就是那一个。"
            extra={
              writable ? (
                <Button
                  size="sm"
                  onClick={() => {
                    setEditingContact(null);
                    setContactForm(emptyContactInput());
                    setActionError(null);
                  }}
                >
                  <Plus className="h-4 w-4" />
                  新建联系人
                </Button>
              ) : undefined
            }
          >
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <Field label="关键词" hint="姓名 / 职务 / 邮箱 / 电话 / 手机">
                  <input
                    className={control}
                    value={contactKeyword}
                    placeholder="搜对接人"
                    onChange={(e) => setContactKeyword(e.target.value)}
                  />
                </Field>
                <Field label="只看默认联系人">
                  <input
                    type="checkbox"
                    checked={contactPrimaryOnly}
                    onChange={(e) => setContactPrimaryOnly(e.target.checked)}
                  />
                </Field>
              </div>

              {contacts.length === 0 ? (
                <EmptyState title="没有联系人" description={contactEmptyHint(contactFiltered)} />
              ) : (
                <div className="space-y-2">
                  {contacts.map((contact) => (
                    <div key={contact.id} className="border-control-border rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{contactDisplayName(contact)}</span>
                        {contact.isPrimary && <Badge tone="success">默认联系人</Badge>}
                        {contact.selectionBlockCode && (
                          <Badge tone="warning">{contactBlockLabel(contact.selectionBlockCode)}</Badge>
                        )}
                      </div>
                      <p className="text-muted-foreground mt-1 text-sm">
                        {[contact.email, contact.phone, contact.mobile].filter(Boolean).join(' · ') ||
                          '未填联系方式'}
                      </p>
                      {writable && (
                        <div className="mt-2 flex flex-wrap gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => {
                              setEditingContact(contact);
                              setContactForm(toContactInput(contact));
                              setActionError(null);
                            }}
                          >
                            编辑
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => togglePrimary(contact, !contact.isPrimary)}
                          >
                            <Star className="h-4 w-4" />
                            {contact.isPrimary ? '取消默认' : '设为默认'}
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            className="text-destructive"
                            onClick={() => removeContact(contact)}
                          >
                            <Trash2 className="h-4 w-4" />
                            删除
                          </Button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </SectionCard>
        </>
      )}

      {editingAddress && writable && (
        <Editor
          title={editingAddress ? '编辑地址' : '新建地址'}
          description="改动地址内容会在历史版本里追加一版；只改用途请用列表上的「设为默认…」按钮，那不会产生新版本。"
          close={() => setEditingAddress(null)}
        >
          <div className="space-y-3">
            <Field label="地址名" hint="同一个供应商常有多处地址，起个名字才好挑">
              <input
                className={control}
                value={addressForm.label ?? ''}
                onChange={(e) => setAddressForm({ ...addressForm, label: e.target.value || null })}
              />
            </Field>
            <Field label="国家" hint="必填：报关、运费分区与税务辖区都读它">
              <select
                className={control}
                value={addressForm.countryCode}
                onChange={(e) => setAddressForm({ ...addressForm, countryCode: e.target.value })}
              >
                {ADDRESS_COUNTRIES.map((country) => (
                  <option key={country.value} value={country.value}>
                    {country.label}（{country.value}）
                  </option>
                ))}
              </select>
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="省 / 州">
                <input
                  className={control}
                  value={addressForm.stateProvince ?? ''}
                  onChange={(e) =>
                    setAddressForm({ ...addressForm, stateProvince: e.target.value || null })
                  }
                />
              </Field>
              <Field label="城市" hint="必填">
                <input
                  className={control}
                  value={addressForm.city}
                  onChange={(e) => setAddressForm({ ...addressForm, city: e.target.value })}
                />
              </Field>
              <Field label="区 / 县">
                <input
                  className={control}
                  value={addressForm.district ?? ''}
                  onChange={(e) => setAddressForm({ ...addressForm, district: e.target.value || null })}
                />
              </Field>
              <Field label="邮编" hint="可以留空：不少国家没有邮编体系">
                <input
                  className={control}
                  value={addressForm.postalCode ?? ''}
                  onChange={(e) => setAddressForm({ ...addressForm, postalCode: e.target.value || null })}
                />
              </Field>
              <Field label="街道地址行 1" hint="必填">
                <input
                  className={control}
                  value={addressForm.streetLine1}
                  onChange={(e) => setAddressForm({ ...addressForm, streetLine1: e.target.value })}
                />
              </Field>
              <Field label="街道地址行 2" hint="门牌号 / 楼层 / Unit">
                <input
                  className={control}
                  value={addressForm.streetLine2 ?? ''}
                  onChange={(e) => setAddressForm({ ...addressForm, streetLine2: e.target.value || null })}
                />
              </Field>
            </div>
            <Field label="变更说明" hint="会写进历史版本那一行，方便日后回看为什么改">
              <input
                className={control}
                value={addressForm.changeNote ?? ''}
                placeholder="例如：门牌号改了，之前的是仓库大门"
                onChange={(e) => setAddressForm({ ...addressForm, changeNote: e.target.value || null })}
              />
            </Field>
            <Field label="用途" hint="可以同时标多个：同一个仓既发货又被提货很常见">
              <div className="space-y-2">
                {ADDRESS_USAGES.map((usage) => {
                  const current = (addressForm.usages ?? []).find(
                    (item) => item?.usage.trim().toUpperCase() === usage.value,
                  );
                  return (
                    <label key={usage.value} className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={Boolean(current)}
                        onChange={(e) => {
                          const rest = (addressForm.usages ?? []).filter(
                            (item) => item?.usage.trim().toUpperCase() !== usage.value,
                          );
                          setAddressForm({
                            ...addressForm,
                            usages: e.target.checked
                              ? [...rest, { usage: usage.value, isDefault: false }]
                              : rest,
                          });
                        }}
                      />
                      <span>{usage.label}</span>
                      <span className="text-muted-foreground text-xs">{usage.hint}</span>
                    </label>
                  );
                })}
              </div>
            </Field>
            {(() => {
              const dropped =
                editingAddress && editingAddress.usages?.length
                  ? removedUsages(editingAddress.usages, addressForm.usages ?? [])
                  : [];
              return dropped.length > 0 ? (
                <p className="text-destructive text-sm">
                  保存后这些用途会被取消：
                  {dropped.map(usageLabel).join('、')}
                  （用途是整体替换语义，少勾一条就等于取消它）
                </p>
              ) : null;
            })()}
            <Button size="sm" onClick={submitAddress}>
              <Save className="h-4 w-4" />
              保存
            </Button>
          </div>
        </Editor>
      )}

      {editingContact && writable && (
        <Editor
          title={editingContact ? '编辑联系人' : '新建联系人'}
          description="邮箱 / 电话 / 手机至少填一项：三项都空的联系人在采购、物流、售后三处都用不了。"
          close={() => setEditingContact(null)}
        >
          <div className="space-y-3">
            <Field label="姓名" hint="必填：主体名不能替代这里，采购单要的是人名">
              <input
                className={control}
                value={contactForm.name}
                onChange={(e) => setContactForm({ ...contactForm, name: e.target.value })}
              />
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="职务">
                <input
                  className={control}
                  value={contactForm.position ?? ''}
                  onChange={(e) => setContactForm({ ...contactForm, position: e.target.value || null })}
                />
              </Field>
              <Field label="邮箱">
                <input
                  className={control}
                  value={contactForm.email ?? ''}
                  onChange={(e) => setContactForm({ ...contactForm, email: e.target.value || null })}
                />
              </Field>
              <Field label="电话">
                <input
                  className={control}
                  value={contactForm.phone ?? ''}
                  placeholder="+86-755-0000-0000"
                  onChange={(e) => setContactForm({ ...contactForm, phone: e.target.value || null })}
                />
              </Field>
              <Field label="手机号">
                <input
                  className={control}
                  value={contactForm.mobile ?? ''}
                  placeholder="138-0000-0000"
                  onChange={(e) => setContactForm({ ...contactForm, mobile: e.target.value || null })}
                />
              </Field>
            </div>
            <Field label="备注">
              <input
                className={control}
                value={contactForm.note ?? ''}
                onChange={(e) => setContactForm({ ...contactForm, note: e.target.value || null })}
              />
            </Field>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={contactForm.isPrimary}
                onChange={(e) => setContactForm({ ...contactForm, isPrimary: e.target.checked })}
              />
              <span>
                设为默认联系人（<Phone className="inline h-3 w-3" /> 原来的默认会自动取消）
              </span>
            </label>
            <Button size="sm" onClick={submitContact}>
              <Save className="h-4 w-4" />
              保存
            </Button>
          </div>
        </Editor>
      )}

      {history && (
        <Editor
          title="地址历史版本"
          description="每一版都是当时的内容，已发生单据引用的就是其中一版 —— 改地址不会改写它们。"
          close={() => setHistory(null)}
        >
          <div className="space-y-3">
            <p className="text-muted-foreground text-sm">
              当前地址：{history.address.oneLine}（第 {history.address.latestVersionNo} 版）
            </p>
            {history.versions.length === 0 ? (
              <EmptyState title="没有历史版本" description={addressVersionEmptyHint()} />
            ) : (
              history.versions.map((version) => (
                <div key={version.versionNo} className="border-control-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">第 {version.versionNo} 版</span>
                    {version.current && <Badge tone="success">当前</Badge>}
                    <span className="text-muted-foreground ml-auto text-xs">
                      {version.changedAt?.slice(0, 19).replace('T', ' ')} UTC
                    </span>
                  </div>
                  <p className="mt-1 text-sm">{version.oneLine}</p>
                  <p className="text-muted-foreground text-xs">{version.changeNote}</p>
                </div>
              ))
            )}
          </div>
        </Editor>
      )}
    </div>
  );
}