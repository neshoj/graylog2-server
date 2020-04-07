// @flow strict
import * as React from 'react';
import { useContext } from 'react';
import PropTypes from 'prop-types';

import { ActionContext } from 'views/logic/ActionContext';
import DecoratorContext from 'views/components/messagelist/decoration/DecoratorContext';

import PossiblyHighlight from './PossiblyHighlight';
import Highlight from './Highlight';

type Props = {
  children: ?React.Node,
  field: string,
  value?: any,
};

const CustomHighlighting = ({ children, field: fieldName, value: fieldValue }: Props) => {
  const { highlightingRules = {} } = useContext(ActionContext);
  const decorators = [];
  const rules = highlightingRules[fieldName] || [];
  rules.forEach((rule) => {
    const ranges = [];
    if (String(fieldValue) === String(rule.value)) {
      ranges.push({
        start: String(fieldValue).indexOf(rule.value),
        length: String(rule.value).length,
      });
    }

    if (ranges.length > 0) {
      decorators.push(({ field, value }) => (
        <PossiblyHighlight field={field}
                           value={value}
                           highlightRanges={ranges.length > 0 ? { [fieldName]: ranges } : {}}
                           color={rule.color} />
      ));
    }
  });
  if (decorators.length === 0) {
    decorators.push(Highlight);
  }
  return (
    <DecoratorContext.Provider value={decorators}>
      {children}
    </DecoratorContext.Provider>
  );
};

CustomHighlighting.propTypes = {
  children: PropTypes.node,
  field: PropTypes.string.isRequired,
  value: PropTypes.any,
};

CustomHighlighting.defaultProps = {
  children: undefined,
  value: undefined,
};

export default CustomHighlighting;
