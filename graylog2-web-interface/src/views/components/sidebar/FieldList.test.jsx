import React from 'react';
import { mount } from 'wrappedEnzyme';

import { simpleFields, simpleQueryFields } from 'fixtures/fields';

import FieldTypesContext from 'views/components/contexts/FieldTypesContext';
import FieldList from './FieldList';

jest.mock('views/stores/ViewMetadataStore', () => ({
  ViewMetadataStore: {
    getInitialState: () => ({
      activeQuery: 'aQueryId',
      id: 'aViewId',
    }),
    listen: jest.fn(),
  },
}));

describe('<FieldList />', () => {
  const fieldTypesStoreState = { all: simpleFields(), queryFields: simpleQueryFields('aQueryId') };
  const SimpleFieldList = () => (
    <FieldTypesContext.Provider value={fieldTypesStoreState}>
      <FieldList listHeight={1000} />
    </FieldTypesContext.Provider>
  );

  it('should render a FieldList', () => {
    const wrapper = mount(<SimpleFieldList />);
    expect(wrapper.find('span.field-element').text()).toBe('http_method');
  });

  it('should render all fields in FieldList after click', () => {
    const wrapper = mount(<SimpleFieldList />);
    expect(wrapper.find('span.field-element').length).toBe(1);

    wrapper.find('a[children="all"]').simulate('click');

    expect(wrapper.find('span.field-element').length).toBe(2);
    expect(wrapper.find('span.field-element').at(0).text()).toBe('date');
    expect(wrapper.find('span.field-element').at(1).text()).toBe('http_method');

    wrapper.find('a[children="current streams"]').simulate('click');
    expect(wrapper.find('span.field-element').length).toBe(1);
    expect(wrapper.find('span.field-element').text()).toBe('http_method');
  });

  it('should search in the field list', () => {
    const wrapper = mount(<SimpleFieldList />);
    expect(wrapper.find('span.field-element').length).toBe(1);

    wrapper.find('a[children="all"]').simulate('click');

    expect(wrapper.find('span.field-element').length).toBe(2);

    wrapper.find('input#common-search-form-query-input').simulate('change', { target: { value: 'http_method' } });

    expect(wrapper.find('span.field-element').length).toBe(1);
    expect(wrapper.find('span.field-element').text()).toBe('http_method');
  });

  it('should show hint when no fields are returned after filtering', () => {
    const hint = <i>No fields to show. Try changing your filter term or select a different field set above.</i>;
    const wrapper = mount(<SimpleFieldList />);
    expect(wrapper).not.toContainReact(hint);

    wrapper.find('input#common-search-form-query-input').simulate('change', { target: { value: 'non_existing_field' } });

    expect(wrapper.find('span.field-element').length).toBe(0);
    expect(wrapper).toContainReact(hint);
  });
});
