#pragma once

#include "indexer/classificator.hpp"
#include "indexer/feature_data.hpp"

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace ftypes
{
// When [allowDuplications] is true then we are support duplications in ierarchy,
// for ex. shop and shop-alcohol. shop-alcohol is duplicaton of shop, because shop contains
// shop-alcohol.
template <typename Container, bool allowDuplications = false>
class Matcher
{
public:
  using ConstIterator = typename Container::const_iterator;

  ConstIterator Find(feature::TypesHolder const & types) const
  {
    for (auto const t : types)
    {
      for (auto level = ftype::GetLevel(t); level; --level)
      {
        auto truncatedType = t;
        ftype::TruncValue(truncatedType, level);
        auto const it = m_mapping.find(truncatedType);

        if (it != m_mapping.cend())
          return it;
      }
    }

    return m_mapping.cend();
  }

  bool IsValid(ConstIterator it) const
  {
    return it != m_mapping.cend();
  }

  bool Contains(feature::TypesHolder const & types) const
  {
    return IsValid(Find(types));
  }
  template <typename Type, typename... Args>
  void AppendType(Type && type, Args &&... args)
  {
#if defined(DEBUG)
      if (!allowDuplications)
      {
        feature::TypesHolder holder;
        holder.Assign(classif().GetTypeByPath(type));
        ASSERT(Find(holder) == m_mapping.cend(), ("This type already exists", type));
      }
#endif
    m_mapping.emplace(classif().GetTypeByPath(std::forward<Type>(type)),
                      std::forward<Args>(args)...);
  }

  template <typename TypesPaths, typename... Args>
  void Append(TypesPaths && types, Args &&... args)
  {
    // We mustn't forward args in the loop below because it will be forwarded at first iteration.
    for (auto const & type : types)
      AppendType(type, args...);
  }

private:
  Container m_mapping;
};

template <typename Key, typename Value>
using HashMapMatcher = Matcher<std::unordered_map<Key, Value>>;

template <typename Key>
using HashSetMatcher = Matcher<std::unordered_set<Key>>;
}  // namespace ftypes
