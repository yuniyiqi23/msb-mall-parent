package com.msb.mall.product.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.msb.mall.product.service.CategoryBrandRelationService;
import com.msb.mall.product.vo.Catalog2VO;
import org.apache.skywalking.apm.toolkit.trace.Tag;
import org.apache.skywalking.apm.toolkit.trace.Tags;
import org.apache.skywalking.apm.toolkit.trace.Trace;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.msb.common.utils.PageUtils;
import com.msb.common.utils.Query;

import com.msb.mall.product.dao.CategoryDao;
import com.msb.mall.product.entity.CategoryEntity;
import com.msb.mall.product.service.CategoryService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


@Service("categoryService")
public class CategoryServiceImpl extends ServiceImpl<CategoryDao, CategoryEntity> implements CategoryService {

    @Autowired
    CategoryBrandRelationService categoryBrandRelationService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<CategoryEntity> page = this.page(
                new Query<CategoryEntity>().getPage(params),
                new QueryWrapper<CategoryEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * ???????????????????????????????????????????????????????????????????????????????????????
     *
     * @param params
     * @return
     */
    @Override
    public List<CategoryEntity> queryPageWithTree(Map<String, Object> params) {
        // 1.?????????????????????????????????
        List<CategoryEntity> categoryEntities = baseMapper.selectList(null);
        // 2.????????????????????????????????????????????????????????????
        // ?????????????????????????????????  parent_cid = 0
        List<CategoryEntity> list = categoryEntities.stream()
                .filter(categoryEntity -> categoryEntity.getParentCid() == 0)
                .map(categoryEntity -> {
                    // ?????????????????????????????????  ?????????????????????
                    categoryEntity.setChildren(getCategoryChildrens(categoryEntity,categoryEntities));
                    return categoryEntity;
                }).sorted((entity1, entity2) -> {
                    return (entity1.getSort() == null ? 0 : entity1.getSort()) - (entity2.getSort() == null ? 0 : entity2.getSort());
                }).collect(Collectors.toList());
        // ???????????????????????????????????????????????????
        return list;
    }

    /**
     * ????????????????????????
     * @param ids
     */
    @Override
    public void removeCategoryByIds(List<Long> ids) {
        // TODO  1.????????????????????????????????????????????????
        // 2.????????????????????????
        baseMapper.deleteBatchIds(ids);

    }

    @Override
    public Long[] findCatelogPath(Long catelogId) {
        List<Long> paths = new ArrayList<>();
        List<Long> parentPath = findParentPath(catelogId, paths);
        Collections.reverse(parentPath);
        return parentPath.toArray(new Long[parentPath.size()]);
    }

    /**
     * @CacheEvict?????????????????????????????????????????????????????????
     * @CacheEvict(value = "catagory",allEntries = true) ????????????catagory?????????????????????????????????
     * @param entity
     */
    //@CacheEvict(value = "catagory",key="'getLeve1Category'")
    /*@Caching(evict = {
            @CacheEvict(value = "catagory",key="'getLeve1Category'")
            ,@CacheEvict(value = "catagory",key="'getCatelog2JSON'")
    })*/
    @CacheEvict(value = "catagory",allEntries = true)
    @Transactional
    @Override
    public void updateDetail(CategoryEntity entity) {
        // ??????????????????
        this.updateById(entity);
        if(!StringUtils.isEmpty(entity.getName())){
            // ???????????????????????????
            categoryBrandRelationService.updateCatelogName(entity.getCatId(),entity.getName());
            // TODO ?????????????????????????????????
        }
    }

    /**
     * ??????????????????????????????(????????????)
     *    ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     *    @Cacheable({"catagory","product"}) ?????????????????????????????????????????????????????????
     *                                       ???????????????????????????????????????????????????????????????????????????????????????
     *                                       ??????????????????????????????????????????????????????????????????????????????????????????
     *    ????????????
     *       1.?????????Redis?????????????????????Key?????????????????????????????????::SimpleKey[]
     *       2.???????????????????????????????????????-1??????
     *       3.????????????????????????????????????jdk??????????????????
     *    ?????????
     *       1.???????????????????????????????????????????????????key??? key????????????????????????????????????????????????????????????SPEL??????????????????#root.method.name
     *       2.?????????????????????????????????: spring.cache.redis.time-to-live ??????????????????
     *       3.???????????????????????????JSON??????
     *   SpringCache?????????
     *     CacheAutoConfiguration--??????????????????spring.cache.type=reids????????? RedisCacheAutoConfiguration
     * @return
     */
    @Trace
    @Cacheable(value = {"catagory"},key = "#root.method.name",sync = true)
    @Override
    public List<CategoryEntity> getLeve1Category() {
        System.out.println("????????????????????????....");
        long start = System.currentTimeMillis();
        List<CategoryEntity> list = baseMapper.queryLeve1Category();
        System.out.println("?????????????????????:" + (System.currentTimeMillis() - start));
        return list;
    }

    /**
     * ?????????????????????????????????????????????
     * @param list
     * @param parentCid
     * @return
     */
    private List<CategoryEntity> queryByParenCid(List<CategoryEntity> list,Long parentCid){
        List<CategoryEntity> collect = list.stream().filter(item -> {
            return item.getParentCid().equals(parentCid);
        }).collect(Collectors.toList());
        return collect;
    }

    // ????????????
    private Map<String,Map<String, List<Catalog2VO>>> cache = new HashMap<>();


    /**
     * ??????????????????????????????????????????????????????????????????
     * @return
     */
    @Trace
    @Tags({
            @Tag(key = "getCatelog2JSON",value = "returnedObj")
    })
    @Cacheable(value = "catagory",key = "#root.methodName")
    @Override
    public Map<String, List<Catalog2VO>> getCatelog2JSON() {
        // ???????????????????????????
        List<CategoryEntity> list = baseMapper.selectList(new QueryWrapper<CategoryEntity>());
        // ????????????????????????????????????
        List<CategoryEntity> leve1Category = this.queryByParenCid(list,0l);
        // ?????????????????????????????????Map?????? key?????????????????????????????? value????????????????????????????????????????????????
        Map<String, List<Catalog2VO>> map = leve1Category.stream().collect(Collectors.toMap(
                key -> key.getCatId().toString()
                , value -> {
                    // ?????????????????????????????????????????????????????????????????????
                    List<CategoryEntity> l2Catalogs = this.queryByParenCid(list,value.getCatId());
                    List<Catalog2VO> Catalog2VOs =null;
                    if(l2Catalogs != null){
                        Catalog2VOs = l2Catalogs.stream().map(l2 -> {
                            // ???????????????????????????????????????????????????????????????Catelog2VO???
                            Catalog2VO catalog2VO = new Catalog2VO(l2.getParentCid().toString(), null, l2.getCatId().toString(), l2.getName());
                            // ???????????????????????????????????????????????????????????????
                            List<CategoryEntity> l3Catelogs = this.queryByParenCid(list,l2.getCatId());
                            if(l3Catelogs != null){
                                // ??????????????????????????????????????????????????????
                                List<Catalog2VO.Catalog3VO> catalog3VOS = l3Catelogs.stream().map(l3 -> {
                                    Catalog2VO.Catalog3VO catalog3VO = new Catalog2VO.Catalog3VO(l3.getParentCid().toString(), l3.getCatId().toString(), l3.getName());
                                    return catalog3VO;
                                }).collect(Collectors.toList());
                                // ??????????????????????????????
                                catalog2VO.setCatalog3List(catalog3VOS);
                            }
                            return catalog2VO;
                        }).collect(Collectors.toList());
                    }

                    return Catalog2VOs;
                }
        ));
        return map;
    }

    /**
     * ????????????????????????????????????????????????
     * ????????????Map<String, Catalog2VO>??????
     * @return
     */
    //@Override
    public Map<String, List<Catalog2VO>> getCatelog2JSONRedis() {
        String key = "catalogJSON";
        // ???Redis????????????????????????
        String catalogJSON = stringRedisTemplate.opsForValue().get(key);
        if(StringUtils.isEmpty(catalogJSON)){
            System.out.println("??????????????????.....");
            // ???????????????????????????????????????????????????
            Map<String, List<Catalog2VO>> catelog2JSONForDb = getCatelog2JSONDbWithRedisson();
            return catelog2JSONForDb;
        }
        System.out.println("???????????????....");
        // ???????????????????????????????????????????????????????????????????????????
        Map<String, List<Catalog2VO>> stringListMap = JSON.parseObject(catalogJSON, new TypeReference<Map<String, List<Catalog2VO>>>() {
        });
        return stringListMap;
    }

    public Map<String, List<Catalog2VO>> getCatelog2JSONDbWithRedisson() {
        String keys = "catalogJSON";
        // ????????????????????????  ???????????????????????????????????????????????????
        // ???????????? product-lock  product-1001-lock product-1002-lock
        RLock lock = redissonClient.getLock("catelog2JSON-lock");
        Map<String, List<Catalog2VO>> data = null;
        try {
            lock.lock();
            // ????????????
            data = getDataForDB(keys);
        }finally {
            lock.unlock();
        }
        return data;
    }


    public Map<String, List<Catalog2VO>> getCatelog2JSONDbWithRedisLock() {
        String keys = "catalogJSON";
        // ?????? ???????????????????????????????????????????????????
        String uuid = UUID.randomUUID().toString();
        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent("lock", uuid,300,TimeUnit.SECONDS);
        if(lock){
            System.out.println("????????????????????????...");
            Map<String, List<Catalog2VO>> data = null;
            try {
                // ????????????
                data = getDataForDB(keys);
            }finally {
                String srcipts = "if redis.call('get',KEYS[1]) == ARGV[1]  then return redis.call('del',KEYS[1]) else  return 0 end ";
                // ??????Redis???lua???????????? ?????????????????????????????????
                stringRedisTemplate.execute(new DefaultRedisScript<Long>(srcipts,Long.class)
                        ,Arrays.asList("lock"),uuid);
            }
            return data;
        }else{
            // ????????????
            // ??????+??????
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("???????????????....");
            return getCatelog2JSONDbWithRedisLock();
        }
    }


    /**
     * ???????????????????????????
     * @param keys
     * @return
     */
    private Map<String, List<Catalog2VO>> getDataForDB(String keys) {
        // ???Redis????????????????????????
        String catalogJSON = stringRedisTemplate.opsForValue().get(keys);
        if(!StringUtils.isEmpty(catalogJSON)){
            // ??????????????????

            // ???????????????????????????????????????????????????????????????????????????
            Map<String, List<Catalog2VO>> stringListMap = JSON.parseObject(catalogJSON, new TypeReference<Map<String, List<Catalog2VO>>>() {
            });
            return stringListMap;
        }
        System.out.println("-----------????????????????????????");

        // ???????????????????????????
        List<CategoryEntity> list = baseMapper.selectList(new QueryWrapper<CategoryEntity>());
        // ????????????????????????????????????
        List<CategoryEntity> leve1Category = this.queryByParenCid(list,0l);
        // ?????????????????????????????????Map?????? key?????????????????????????????? value????????????????????????????????????????????????
        Map<String, List<Catalog2VO>> map = leve1Category.stream().collect(Collectors.toMap(
                key -> key.getCatId().toString()
                , value -> {
                    // ?????????????????????????????????????????????????????????????????????
                    List<CategoryEntity> l2Catalogs = this.queryByParenCid(list,value.getCatId());
                    List<Catalog2VO> Catalog2VOs =null;
                    if(l2Catalogs != null){
                        Catalog2VOs = l2Catalogs.stream().map(l2 -> {
                            // ???????????????????????????????????????????????????????????????Catelog2VO???
                            Catalog2VO catalog2VO = new Catalog2VO(l2.getParentCid().toString(), null, l2.getCatId().toString(), l2.getName());
                            // ???????????????????????????????????????????????????????????????
                            List<CategoryEntity> l3Catelogs = this.queryByParenCid(list,l2.getCatId());
                            if(l3Catelogs != null){
                                // ??????????????????????????????????????????????????????
                                List<Catalog2VO.Catalog3VO> catalog3VOS = l3Catelogs.stream().map(l3 -> {
                                    Catalog2VO.Catalog3VO catalog3VO = new Catalog2VO.Catalog3VO(l3.getParentCid().toString(), l3.getCatId().toString(), l3.getName());
                                    return catalog3VO;
                                }).collect(Collectors.toList());
                                // ??????????????????????????????
                                catalog2VO.setCatalog3List(catalog3VOS);
                            }
                            return catalog2VO;
                        }).collect(Collectors.toList());
                    }

                    return Catalog2VOs;
                }
        ));
        // ?????????????????????????????????????????? ???????????????????????????????????????
        //cache.put("getCatelog2JSON",map);
        // ???????????????????????????????????????????????????????????????????????????
        if(map == null){
            // ????????????????????????????????????  ??????????????????
            stringRedisTemplate.opsForValue().set(keys,"1",5, TimeUnit.SECONDS);
        }else{
            // ???????????????????????????????????????????????????????????????????????????
            // ??????????????????
            String json = JSON.toJSONString(map);
            stringRedisTemplate.opsForValue().set("catalogJSON",json,100,TimeUnit.MINUTES);
        }
        return map;
    }

    /**
     * ???????????????????????????
     * ????????????????????????????????????????????????
     * ????????????Map<String, Catalog2VO>??????
     * ???SpringBoot?????????????????????????????????
     * @return
     */
    public Map<String, List<Catalog2VO>> getCatelog2JSONForDb() {
        String keys = "catalogJSON";
        synchronized (this){
            // ???Redis????????????????????????
            String catalogJSON = stringRedisTemplate.opsForValue().get(keys);
            if(!StringUtils.isEmpty(catalogJSON)){
                // ??????????????????
                // ???????????????????????????????????????????????????????????????????????????
                Map<String, List<Catalog2VO>> stringListMap = JSON.parseObject(catalogJSON, new TypeReference<Map<String, List<Catalog2VO>>>() {
                });
                return stringListMap;
            }
            System.out.println("-----------????????????????????????");

            // ???????????????????????????
            List<CategoryEntity> list = baseMapper.selectList(new QueryWrapper<CategoryEntity>());
            // ????????????????????????????????????
            List<CategoryEntity> leve1Category = this.queryByParenCid(list,0l);
            // ?????????????????????????????????Map?????? key?????????????????????????????? value????????????????????????????????????????????????
            Map<String, List<Catalog2VO>> map = leve1Category.stream().collect(Collectors.toMap(
                    key -> key.getCatId().toString()
                    , value -> {
                        // ?????????????????????????????????????????????????????????????????????
                        List<CategoryEntity> l2Catalogs = this.queryByParenCid(list,value.getCatId());
                        List<Catalog2VO> Catalog2VOs =null;
                        if(l2Catalogs != null){
                            Catalog2VOs = l2Catalogs.stream().map(l2 -> {
                                // ???????????????????????????????????????????????????????????????Catelog2VO???
                                Catalog2VO catalog2VO = new Catalog2VO(l2.getParentCid().toString(), null, l2.getCatId().toString(), l2.getName());
                                // ???????????????????????????????????????????????????????????????
                                List<CategoryEntity> l3Catelogs = this.queryByParenCid(list,l2.getCatId());
                                if(l3Catelogs != null){
                                    // ??????????????????????????????????????????????????????
                                    List<Catalog2VO.Catalog3VO> catalog3VOS = l3Catelogs.stream().map(l3 -> {
                                        Catalog2VO.Catalog3VO catalog3VO = new Catalog2VO.Catalog3VO(l3.getParentCid().toString(), l3.getCatId().toString(), l3.getName());
                                        return catalog3VO;
                                    }).collect(Collectors.toList());
                                    // ??????????????????????????????
                                    catalog2VO.setCatalog3List(catalog3VOS);
                                }
                                return catalog2VO;
                            }).collect(Collectors.toList());
                        }

                        return Catalog2VOs;
                    }
            ));
            // ?????????????????????????????????????????? ???????????????????????????????????????
            //cache.put("getCatelog2JSON",map);
            // ???????????????????????????????????????????????????????????????????????????
            if(map == null){
                // ????????????????????????????????????  ??????????????????
                stringRedisTemplate.opsForValue().set(keys,"1",5, TimeUnit.SECONDS);
            }else{
                // ???????????????????????????????????????????????????????????????????????????
                // ??????????????????
                String json = JSON.toJSONString(map);
                stringRedisTemplate.opsForValue().set("catalogJSON",json,100,TimeUnit.MINUTES);
            }
            return map;
        } }


    /**
     * 225,22,2
     * @param catelogId
     * @param paths
     * @return
     */
    private List<Long> findParentPath(Long catelogId,List<Long> paths){
        paths.add(catelogId);
        CategoryEntity entity = this.getById(catelogId);
        if(entity.getParentCid() != 0){
            findParentPath(entity.getParentCid(),paths);
        }
        return paths;
    }

    /**
     *  ????????????????????????????????????  ????????????
     * @param categoryEntity ????????????
     * @param categoryEntities ?????????????????????
     * @return
     */
    private List<CategoryEntity> getCategoryChildrens(CategoryEntity categoryEntity
            , List<CategoryEntity> categoryEntities) {
        List<CategoryEntity> collect = categoryEntities.stream().filter(entity -> {
            // ???????????????????????????????????????
            // ?????? Long ???????????? ?????? -128 127?????????????????? new Long() ??????
            return entity.getParentCid().equals(categoryEntity.getCatId());
        }).map(entity -> {
            // ????????????????????????????????????????????????
            entity.setChildren(getCategoryChildrens(entity, categoryEntities));
            return entity;
        }).sorted((entity1, entity2) -> {
            return (entity1.getSort() == null ? 0 : entity1.getSort()) - (entity2.getSort() == null ? 0 : entity2.getSort());
        }).collect(Collectors.toList());
        return collect;
    }
}